import http from "node:http";
import dgram from "node:dgram";
import { WebSocketServer } from "ws";

const host = process.env.QUALITY_LISTEN || "127.0.0.1";
const httpPort = Number(process.env.QUALITY_HTTP_PORT || 8787);
const udpPort = Number(process.env.QUALITY_UDP_PORT || 8788);
const rooms = new Map();
const rate = new Map();
const pendingTtlMs = 20_000;
const roomPattern = /^[A-Za-z0-9_-]{12,64}$/;

const app = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, {"content-type":"application/json", "cache-control":"no-store"});
    res.end(JSON.stringify({ok:true, protocol:"speedtester-quality-relay-v1"}));
    return;
  }
  res.writeHead(404, {"content-type":"text/plain"}).end("Not found");
});
const wsServer = new WebSocketServer({server:app, path:"/ws", maxPayload:2048});
function send(ws, message) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(message));
}
function roomFor(code) {
  if (!rooms.has(code)) rooms.set(code,{sender:null,receiver:null,pending:new Map()});
  return rooms.get(code);
}
wsServer.on("connection",(ws,req)=>{
  const url = new URL(req.url, "http://localhost");
  const code = url.searchParams.get("room") || "";
  const role = url.searchParams.get("role");
  if (!roomPattern.test(code) || !["sender","receiver"].includes(role)) {
    ws.close(1008,"Invalid pairing code or role");
    return;
  }
  const room = roomFor(code);
  if (room[role] && room[role].readyState === 1) {
    ws.close(1008,"Role already connected");
    return;
  }
  room[role] = ws;
  send(room.sender,{type:"ready",receivers:room.receiver?.readyState===1?1:0});
  ws.on("message",raw=>{
    let msg;
    try {msg=JSON.parse(raw.toString("utf8"))} catch {return}
    if (role === "sender" && msg.type === "message" &&
        typeof msg.id === "string" && /^[a-z0-9-]{32,48}$/i.test(msg.id) &&
        typeof msg.payload === "string" && msg.payload.length <= 256) {
      if (room.pending.size >= 30 || !room.receiver || room.receiver.readyState !== 1) return;
      room.pending.set(msg.id,Date.now());
      send(room.receiver,{type:"message",id:msg.id,payload:msg.payload});
    } else if (role === "receiver" && msg.type === "delivered" &&
               typeof msg.id === "string" && room.pending.has(msg.id)) {
      room.pending.delete(msg.id);
      send(room.sender,{type:"delivered",id:msg.id});
    }
  });
  ws.on("close",()=>{
    if (room[role] === ws) room[role] = null;
    room.pending.clear();
    send(room.sender,{type:"ready",receivers:room.receiver?.readyState===1?1:0});
    if (!room.sender && !room.receiver) rooms.delete(code);
  });
});
const udp = dgram.createSocket("udp4");
udp.on("message",(message,peer)=>{
  if (message.length < 10 || message.length > 100) return;
  if (!/^STQ1\|[A-Za-z0-9]{16}\|(?:[0-9]|1[01])$/.test(message.toString("ascii"))) return;
  const key = peer.address;
  const now = Date.now();
  const entry = rate.get(key) || {ts:now,n:0};
  if (now - entry.ts > 1000) {entry.ts = now;entry.n=0}
  if (++entry.n > 32) return;
  rate.set(key,entry);
  udp.send(message,peer.port,peer.address);
});
setInterval(()=>{
  const now=Date.now();
  for (const [code,room] of rooms) {
    for (const [id,stamp] of room.pending) if (now-stamp > pendingTtlMs) room.pending.delete(id);
    if (!room.sender && !room.receiver) rooms.delete(code);
  }
  for (const [key,v] of rate) if(now-v.ts > 5000) rate.delete(key);
},5000).unref();
udp.bind(udpPort,host,()=>console.log("UDP quality echo:",host,udpPort));
app.listen(httpPort,host,()=>console.log("HTTP/WebSocket quality relay:",host,httpPort));
const shutdown=()=>{wsServer.close();udp.close();app.close()};
process.on("SIGINT",shutdown);
process.on("SIGTERM",shutdown);
