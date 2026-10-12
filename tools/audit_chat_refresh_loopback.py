import asyncio,json,os
from aiohttp import web, WSMsgType

PORT=19079
state={}
release=asyncio.Event()
connections={}
def fresh():
    return {'mode':'normal','held':False,'upgrades':0,'refreshes':0,'auth':[],'messages':[],'requests':[],'failures':[]}
def response(value,status=200):return web.json_response(value,status=status)
async def process(request):
    global state,release
    path=request.path
    query=request.query
    if path.startswith('/audit/'):
        if path=='/audit/prepare':
            release.set()
            for ws in list(connections.values()):await ws.close(code=1000)
            connections.clear()
            state=fresh()
            release=asyncio.Event()
        elif path=='/audit/mode':state['mode']=query['value']
        elif path=='/audit/release':release.set()
        elif path=='/audit/close-old':await connections[1].close(code=1008,message=b'local expired old token')
        return response(state if path=='/audit/state' else {'ok':True})
    bearer=request.headers.get('Authorization')
    state['requests'].append({'path':path,'method':request.method,'bearer':bearer,'query':request.query_string})
    if path=='/ws/bookings/42':
        state['upgrades']+=1
        index=state['upgrades']
        if state['mode'].startswith('held') and index==1:
            state['held']=True
            try:await asyncio.wait_for(release.wait(),30)
            except asyncio.TimeoutError:
                state['failures'].append('Held upgrade expired')
                return response({},503)
        return await handle(request,index)
    if path=='/auth/verify':
        payload=await request.json()
        if payload.get('name')!='A':state['failures'].append('Unexpected auth account')
        return response({'access_token':'access-A','refresh_token':'refresh-A'})
    if path=='/trusted-contacts':return response({'items':[]},401 if bearer=='Bearer access-A' else 200)
    if path=='/auth/refresh':
        state['refreshes']+=1
        payload=await request.json()
        if payload.get('refresh_token')!='refresh-A' or len(payload.get('rotation_id',''))!=64:
            state['failures'].append('Unexpected refresh payload')
        return response({'access_token':'access-A2','refresh_token':'refresh-A2'})
    if path in ('/auth/logout','/push/unregister'):return response({'ok':True})
    state['failures'].append('Unexpected path '+path)
    return response({},404)
async def handle(request,index):
    connection=web.WebSocketResponse(autoping=True,compress=False)
    await connection.prepare(request)
    current=state
    connections[index]=connection
    try:
        async for frame in connection:
            if frame.type!=WSMsgType.TEXT:continue
            body=json.loads(frame.data)
            if body.get('type')=='auth':
                current['auth'].append({'connection':index,'token':body['token']})
                if current['mode']=='held-reject':await connection.close(code=1008,message=b'local current-token policy refusal')
                else:await connection.send_json({'type':'message','id':index,'sender_id':22,'text':'ready-'+str(index)})
            elif body.get('type')=='message':current['messages'].append({'connection':index,'text':body['text']})
            else:current['failures'].append('Unexpected frame type')
    except Exception as exc:current['failures'].append(type(exc).__name__+':'+str(exc))
    finally:
        if connections.get(index) is connection:connections.pop(index,None)
        print(json.dumps({'closed':index,'state':current}),flush=True)
    return connection
async def main():
    global state
    state=fresh()
    app=web.Application()
    app.router.add_route('*','/{path:.*}',process)
    runner=web.AppRunner(app,access_log=None,shutdown_timeout=3)
    await runner.setup()
    await web.TCPSite(runner,'127.0.0.1',PORT).start()
    print(json.dumps({'ready':True,'port':PORT,'pid':os.getpid(),'aiohttp':__import__('aiohttp').__version__}),flush=True)
    await asyncio.Future()
asyncio.run(main())
