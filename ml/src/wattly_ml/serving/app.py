from contextlib import asynccontextmanager
from datetime import datetime, timezone
import os
import logging
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, model_validator

from ..artifacts import current_bundle
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


class ForecastRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    schemaVersion: Literal[1]
    requestId: str = Field(min_length=1,max_length=128)
    asOf: AwareDatetime
    intervalMinutes: Literal[30]
    horizonCount: Literal[24]
    mappingVerified: bool
    priceHistory: list[Price] = Field(min_length=1,max_length=1441)
    weather: list[Weather] = Field(default_factory=list,max_length=100)

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
        return self


def create_app(loader=None):
    @asynccontextmanager
    async def lifespan(app):
        app.state.bundle = None
        app.state.unavailable = "MODEL_ABSENT"
        try:
            if loader:
                app.state.bundle = loader()
            else:
                home = os.environ.get("WATTLY_ML_HOME")
                if not home:
                    raise ValueError("Set WATTLY_ML_HOME to the trusted artifact directory")
                app.state.bundle = current_bundle(Path(home))
            app.state.unavailable = None
        except Exception as exc:
            app.state.unavailable = type(exc).__name__ + ": " + str(exc)
            logging.getLogger("wattly_ml").warning("Model not ready: %s",app.state.unavailable)
        yield
    app = FastAPI(title="Wattly internal inference",lifespan=lifespan)

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
        if app.state.bundle is None:
            return JSONResponse(status_code=503,content={"ready":False,"reason":app.state.unavailable})
        return {"ready":True,"modelVersion":app.state.bundle["manifest"]["version"]}

    @app.post("/forecast")
    def forecast(request: ForecastRequest):
        if app.state.bundle is None:
            raise HTTPException(503,detail={"code":"MODEL_UNAVAILABLE","reason":app.state.unavailable})
        if not request.mappingVerified:
            raise HTTPException(503,detail={"code":"PERIOD_MAPPING_UNVERIFIED"})
        from ..timebase import instant
        manifest = app.state.bundle["manifest"]
        if max(instant(manifest["usableFrom"]),instant(manifest["trainingDate"]))>request.asOf:
            raise HTTPException(503,detail={"code":"MODEL_FROM_FUTURE"})
        if (request.asOf-max(r.sourceUpdatedAt for r in request.priceHistory)).total_seconds()>2400:
            raise HTTPException(503,detail={"code":"STALE_INPUT"})
        history = {r.periodStart:r.model_dump() for r in request.priceHistory}
        try:
            predictions = predict(app.state.bundle,history,request.asOf)
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
        return {"schemaVersion":1,"requestId":request.requestId,"asOf":request.asOf,
                "generatedAt":datetime.now(timezone.utc),"units":"SGD_PER_MWH","modelType":"AI",
                "modelVersion":manifest["version"],"usableFrom":manifest["usableFrom"],
                "baselineRanking":manifest["baselineRanking"],"manifest":manifest,
                "points":points,"qualityFlags":["PRICE_CALENDAR_ONLY"]+(["RETROSPECTIVE_TRAINING"] if manifest["retrospective"] else [])}
    return app


app = create_app()
