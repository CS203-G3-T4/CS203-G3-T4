"""Actual PostgreSQL read-only export, enabled explicitly in CI/test database."""
from contextlib import contextmanager
from datetime import timedelta
import json,os,uuid

import pytest
from wattly_ml import weekly_sources as source
from wattly_ml.data import load_dataset
from wattly_ml.timebase import instant,DAY


@pytest.fixture
def postgres(monkeypatch):
    url=os.environ.get('WATTLY_WEEKLY_TEST_DATABASE_URL')
    if not url:pytest.skip('Set a disposable WATTLY_WEEKLY_TEST_DATABASE_URL')
    import psycopg
    from psycopg import sql
    from psycopg.conninfo import make_conninfo
    schema='weekly_test_'+uuid.uuid4().hex
    with psycopg.connect(url,autocommit=True) as conn:
        conn.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))
        conn.execute(sql.SQL('CREATE TABLE {}.market_price_revision (id bigint,source text,period_start timestamptz,published_at timestamptz,available_at timestamptz,usep numeric,demand numeric,vcp numeric,price_status text,time_mapping text)').format(sql.Identifier(schema)))
        try:
            scoped=make_conninfo(url,options='-c search_path='+schema)
            monkeypatch.setenv('WATTLY_TRAINING_DATABASE_URL',scoped)
            yield conn,schema
        finally:conn.execute(sql.SQL('DROP SCHEMA {} CASCADE').format(sql.Identifier(schema)))


def test_actual_read_only_transaction_refuses_writes(postgres):
    import psycopg
    with source.postgres_connection('WATTLY_TRAINING_DATABASE_URL') as conn:
        assert conn.execute('SHOW transaction_read_only').fetchone()[0]=='on'
        assert conn.execute('SHOW transaction_isolation').fetchone()[0]=='repeatable read'
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
            conn.execute("INSERT INTO market_price_revision(id) VALUES (999)")
        conn.rollback()


def test_snapshot_preserves_revisions_receipts_source_and_cutoff(postgres,tmp_path):
    from psycopg import sql
    conn,schema=postgres;now=instant('2026-01-21T03:00:00+08:00');end=instant('2026-01-20T00:00:00+08:00');cutoff=end-7*DAY
    period=end-DAY
    table=sql.SQL('{}.market_price_revision').format(sql.Identifier(schema))
    rows=[(1,'NEMS_SN_SG',period,period+timedelta(minutes=1),period+timedelta(minutes=2),100,None,None,'PROVISIONAL','UNVERIFIED_FLOOR'),
          (2,'NEMS_SN_SG',period,period+timedelta(minutes=5),period+timedelta(minutes=6),110,None,None,'PROVISIONAL','UNVERIFIED_FLOOR'),
          (3,'NEMS_SN_SG',period,period+timedelta(minutes=1),now+DAY,999,None,None,'PROVISIONAL','UNVERIFIED_FLOOR'),
          (4,'OTHER',period,period+timedelta(minutes=1),period+timedelta(minutes=2),999,None,None,'PROVISIONAL','UNVERIFIED_FLOOR'),
          (5,'NEMS_SN_SG',period,None,None,999,None,None,'FINAL','EMC_PERIOD')]
    with conn.cursor() as cur:cur.executemany(sql.SQL('INSERT INTO {} VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)').format(table),rows)
    cfg={'source':{'kind':'postgres','dsnEnvironment':'WATTLY_TRAINING_DATABASE_URL','name':'NEMS_SN_SG'}}
    directory,manifest=source.acquire(cfg,tmp_path,cutoff,end,now)
    exported,_=load_dataset(directory)
    assert [r['revisionId'] for r in exported]==['1','2']
    assert [r['usep'] for r in exported]==[100,110]
    assert instant(exported[0]['availableAt'])==rows[0][4]
    assert instant(exported[1]['sourceUpdatedAt'])==rows[1][3]
    assert manifest['quality']['publicationVintages'] and manifest['sources'][0]['readOnly']
    assert 'WATTLY_TRAINING_DATABASE_URL' not in (directory/'manifest.json').read_text()
    assert conn.execute(sql.SQL('SELECT count(*) FROM {}').format(table)).fetchone()[0]==5
