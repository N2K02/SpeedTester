import WebSocket from "ws";

const [endpoint, room] = process.argv.slice(2);
if (!endpoint?.startsWith("wss://") || !/^[A-Za-z0-9_-]{12,64}$/.test(room || "")) {
  console.error("Usage: node receiver.mjs wss://your-relay.example/ws strong-pairing-room-code");
  process.exit(1);
}
const url = new URL(endpoint);
url.searchParams.set("role","receiver");
url.searchParams.set("room",room);
const socket = new WebSocket(url.toString());
socket.on("open",()=>console.log("Receiver connected. Leave this window open during the phone test."));
socket.on("message",raw=>{
  let msg;
  try {msg=JSON.parse(raw.toString("utf8"))} catch {return}
  if(msg.type==="message" && /^[a-z0-9-]{32,48}$/i.test(msg.id || "") &&
      typeof msg.payload==="string" && msg.payload.startsWith("speedtester-test-")) {
    socket.send(JSON.stringify({type:"delivered",id:msg.id}));
    console.log("Delivered synthetic test message",msg.id.slice(0,8));
  }
});
socket.on("error",error=>console.error("Relay connection failed:",error.message));
socket.on("close",()=>console.log("Receiver disconnected"));
