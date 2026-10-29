from contextlib import asynccontextmanager
from datetime import datetime, timezone
import os
import logging
import threading
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator

from ..artifacts import current_bundle, load_bundle
from ..data import digest
from ..data import KnownPrices
from ..backtest import predict
from ..spikes import reference
from ..timebase import DAY, floor, targets


class Price(BaseModel):
    model_config = ConfigDict(extra="forbid")
    periodStart: AwareDatetime
    sourceUpdatedAt: AwareDatetime
    availableAt: AwareDatetime
    usep: float = Field(allow_inf_nan=False)


class Weather(BaseModel):
    model_config = ConfigDict(extra="forbid")
    issuedAt: AwareDatetime
    updatedAt: AwareDatetime
    availableAt: AwareDatetime
    validStart: AwareDatetime
    validEnd: AwareDatetime


class ExpectedDemand(BaseModel):
    model_config = ConfigDict(extra='forbid')
    targetPeriod: AwareDatetime
    publishedAt: AwareDatetime
    availableAt: AwareDatetime
    demandMw: float = Field(ge=0,allow_inf_nan=False)
    source: str = Field(min_length=1,max_length=200)


class ForecastRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schemaVersion: Literal[1,2]
    requestId: str = Field(min_length=1,max_length=128)
    asOf: AwareDatetime
    intervalMinutes: Literal[30]
    horizonCount: Literal[24]
    mappingVerified: bool
    priceHistory: list[Price] = Field(min_length=1,max_length=1441)
    weather: list[Weather] = Field(default_factory=list,max_length=100)
    expectedDemand: list[ExpectedDemand] = Field(default_factory=list,max_length=1440)

    @model_validator(mode="after")
    def temporal_contract(self):
        previous = None
        for row in self.priceHistory:
            if row.periodStart != floor(row.periodStart) or (previous is not None and row.periodStart<=previous):
                raise ValueError("History must contain unique ordered half-hours")
            if max(row.periodStart,row.sourceUpdatedAt,row.availableAt)>self.asOf or row.periodStart<self.asOf-29*DAY:
                raise ValueError("Future or out-of-window input")
            previous = row.periodStart
        for row in self.weather:
            if max(row.issuedAt,row.updatedAt,row.availableAt)>self.asOf or row.validStart>=row.validEnd:
                raise ValueError("Future or invalid weather")
        seen = {}
        for row in self.expectedDemand:
            if row.targetPeriod != floor(row.targetPeriod) or row.publishedAt > row.availableAt or row.availableAt > self.asOf:
                raise ValueError('Future or invalid expected-demand vintage')
            key = (row.targetPeriod,row.publishedAt,row.availableAt)
            if key in seen and seen[key] != row.demandMw:
                raise ValueError('Conflicting expected-demand vintages')
            seen[key] = row.demandMw
        return self


def experimental_path(home):
    version = os.environ.get('WATTLY_EXPERIMENTAL_MODEL_VERSION', '')
    pin = os.environ.get('WATTLY_EXPERIMENTAL_MANIFEST_SHA256', '')
    path = (home/'models'/version).resolve()
    if (not version or path.parent != (home/'models').resolve() or len(pin) != 64
            or digest(path/'manifest.json') != pin):
        raise ValueError('Pinned compatible experimental model required')
    return path


def experimental_bundle(home):
    bundle = load_bundle(experimental_path(home))
    if bundle['manifest'].get('productionEligible') is not False:
        raise ValueError('Experimental serving requires an explicitly unapproved bundle')
    return bundle


def create_app(loader=None):
    @asynccontextmanager
    async def lifespan(app):
        app.state.reload_lock = threading.Lock()
        app.state.pointer = None
        app.state.bundle = None
        app.state.experimental = loader is None and os.environ.get('WATTLY_ALLOW_EXPERIMENTAL_AI') == 'true'
        app.state.unavailable = "MODEL_ABSENT"
        try:
            if loader:
                app.state.bundle = loader()
            else:
                home = os.environ.get("WATTLY_ML_HOME")
                if not home:
                    raise ValueError("Set WATTLY_ML_HOME to the trusted artifact directory")
                if app.state.experimental:
                    app.state.bundle = experimental_bundle(Path(home))
                else:
                    if os.environ.get('WATTLY_EXPERIMENTAL_MODEL_VERSION'):
                        raise ValueError('Experimental serving requires explicit opt-in')
                    app.state.bundle = current_bundle(Path(home))
                app.state.pointer = b'EXPERIMENTAL' if app.state.experimental else (Path(home)/'current.json').read_bytes()
            app.state.unavailable = None
        except Exception as exc:
            app.state.bundle = None
            app.state.unavailable = type(exc).__name__ + ": " + str(exc)
            logging.getLogger("wattly_ml").warning("Model not ready: %s",app.state.unavailable)
        yield
    app = FastAPI(title="Wattly internal inference",lifespan=lifespan)

    def refresh_bundle():
        if loader:
            return
        home = Path(os.environ.get('WATTLY_ML_HOME','/nonexistent-wattly-home'))
        with app.state.reload_lock:
            try:
                if app.state.experimental:
                    path = experimental_path(home)
                    if app.state.bundle is None:
                        app.state.bundle = experimental_bundle(home)
                    app.state.unavailable = None
                    return
                pointer = (home/'current.json').read_bytes()
                if pointer != app.state.pointer:
                    bundle = current_bundle(home)
                    app.state.bundle, app.state.pointer, app.state.unavailable = bundle, pointer, None
            except (OSError, ValueError, KeyError) as exc:
                app.state.bundle = None
                app.state.pointer = None
                app.state.unavailable = 'MODEL_RELOAD_FAILED: '+str(exc)

    @app.middleware("http")
    async def bounded_body(request: Request, call_next):
        # Bound chunked requests too, before Pydantic parses the arrays.
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data)>512_000:
                return JSONResponse(status_code=413,content={"code":"PAYLOAD_TOO_LARGE"})
        request._body = bytes(data)
        return await call_next(request)

    @app.get("/health")
    def health():
        return {"status":"UP"}

    @app.get("/ready")
    def ready():
        refresh_bundle()
        bundle = app.state.bundle
        if bundle is None:
            return JSONResponse(status_code=503,content={"ready":False,"reason":app.state.unavailable})
        return {"ready":True,"modelVersion":bundle["manifest"]["version"],"servingMode":"EXPERIMENTAL" if app.state.experimental else "APPROVED"}

    @app.post("/forecast")
    def forecast(request: ForecastRequest):
        refresh_bundle()
        # One immutable bundle per request, even when another request reloads the pointer.
        bundle = app.state.bundle
        if bundle is None:
            raise HTTPException(503,detail={"code":"MODEL_UNAVAILABLE","reason":app.state.unavailable})
        if not request.mappingVerified:
            raise HTTPException(503,detail={"code":"PERIOD_MAPPING_UNVERIFIED"})
        from ..timebase import instant
        manifest = bundle["manifest"]
        if max(instant(manifest["usableFrom"]),instant(manifest["trainingDate"]))>request.asOf:
            raise HTTPException(503,detail={"code":"MODEL_FROM_FUTURE"})
        if (request.asOf-max(r.sourceUpdatedAt for r in request.priceHistory)).total_seconds()>2400:
            raise HTTPException(503,detail={"code":"STALE_INPUT"})
        history = KnownPrices({r.periodStart:r.model_dump() for r in request.priceHistory},
                              [r.model_dump() for r in request.expectedDemand])
        try:
            predictions = predict(bundle,history,request.asOf)
        except Exception:
            raise HTTPException(503,detail={"code":"MODEL_INFERENCE_FAILED"})
        import math
        if len(predictions)!=24 or not all(math.isfinite(v) for v in predictions):
            raise HTTPException(503,detail={"code":"INVALID_MODEL_OUTPUT"})
        points = []
        for h,(target,value) in enumerate(zip(targets(request.asOf),predictions),1):
            ref = reference(history,target,request.asOf,manifest["spikeConfig"])
            points.append({"horizon":h,"targetPeriod":target,"predictedUsep":value,
                           "spikeThreshold":ref["threshold"], "spikeFlag":value>ref["threshold"] if ref["available"] else None,
                           "assessmentAvailable":ref["available"]})
        return {"schemaVersion":request.schemaVersion,"requestId":request.requestId,"asOf":request.asOf,
                "generatedAt":datetime.now(timezone.utc),"units":"SGD_PER_MWH","modelType":"AI",
                "modelVersion":manifest["version"],"usableFrom":manifest["usableFrom"],
                "servingMode":"EXPERIMENTAL" if app.state.experimental else "APPROVED",
                "baselineRanking":manifest["baselineRanking"],"manifest":manifest,
                "points":points,"qualityFlags":(['PRICE_CALENDAR_ONLY'] if manifest.get('featureSchemaVersion',1)==1 else
                    ['PRICE_CALENDAR_HOLIDAY_DEMAND', 'EXPECTED_DEMAND_PARTIAL_OR_MISSING'] if len({r.targetPeriod for r in request.expectedDemand if r.targetPeriod in targets(request.asOf)})<24 else ['PRICE_CALENDAR_HOLIDAY_DEMAND'])+
                    (["RETROSPECTIVE_TRAINING"] if manifest["retrospective"] else [])+
                    (["EXPERIMENTAL_AI"] if app.state.experimental else [])}
    return app


app = create_app()
