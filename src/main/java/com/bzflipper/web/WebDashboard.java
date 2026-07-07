package com.bzflipper.web;

import com.bzflipper.api.FlipCandidate;
import com.bzflipper.config.FlipConfig;
import com.bzflipper.mc.BazaarMacro;
import com.bzflipper.mc.OrderParser.ParsedOrder;
import com.bzflipper.track.ProfitTracker;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Localhost-only live dashboard (http://localhost:{port}).
 *
 * Zero dependencies: the JDK's built-in HttpServer serves one self-contained
 * HTML page plus a JSON stats endpoint the page polls every 1.5s. Control
 * buttons only SET atomic flags — the game thread picks them up in its tick
 * (never touch game state from HTTP threads).
 */
public class WebDashboard {

    private static final Gson GSON = new Gson();

    private final FlipConfig config;
    private final BazaarMacro macro;
    private HttpServer server;
    private ScheduledExecutorService sampler;

    /** Profit history samples: [epochMillis, sessionProfit, projected]. */
    private final List<double[]> history = java.util.Collections.synchronizedList(new ArrayList<>());

    public WebDashboard(FlipConfig config, BazaarMacro macro) {
        this.config = config;
        this.macro = macro;
    }

    public void start() {
        if (!config.webDashboard || server != null) return;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.webPort), 0);
            server.createContext("/", ex -> respond(ex, 200, "text/html", PAGE));
            server.createContext("/api/stats", ex -> respond(ex, 200, "application/json", statsJson()));
            server.createContext("/api/toggle", ex -> { macro.webToggle.set(true); respond(ex, 200, "application/json", "{\"ok\":true}"); });
            server.createContext("/api/panic",  ex -> { macro.webPanic.set(true);  respond(ex, 200, "application/json", "{\"ok\":true}"); });
            server.createContext("/api/dryrun", ex -> {
                config.dryRun = !config.dryRun;
                config.save();
                respond(ex, 200, "application/json", "{\"dryRun\":" + config.dryRun + "}");
            });
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bzflipper-web");
                t.setDaemon(true);
                return t;
            }));
            server.start();

            sampler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bzflipper-web-sampler");
                t.setDaemon(true);
                return t;
            });
            sampler.scheduleWithFixedDelay(this::sample, 2, 15, TimeUnit.SECONDS);

            System.out.println("[bzflipper] dashboard: http://localhost:" + config.webPort);
        } catch (Exception e) {
            System.err.println("[bzflipper] dashboard failed to start: " + e.getMessage());
            server = null;
        }
    }

    private void sample() {
        try {
            history.add(new double[]{System.currentTimeMillis(),
                    macro.getTracker().total(), macro.projectedProfit()});
            while (history.size() > 4000) history.remove(0);
        } catch (Exception ignored) {
        }
    }

    private String statsJson() {
        ProfitTracker t = macro.getTracker();
        Map<String, Object> m = new HashMap<>();
        m.put("enabled", macro.isEnabled());
        m.put("dryRun", config.dryRun);
        m.put("phase", macro.getState().toString());
        m.put("status", macro.statusLine);
        m.put("purse", macro.purse);
        m.put("profit", t.total());
        m.put("allTime", macro.allTimeProfit);
        m.put("perHourNow", t.recentPerHour());
        m.put("perHourSession", t.sessionPerHour());
        m.put("projected", macro.projectedProfit());
        m.put("perHourProjected", t.sessionPerHour(macro.projectedProfit()));
        m.put("uptimeSec", t.elapsedSeconds());
        m.put("cookie", macro.cookieStatus);
        m.put("apiAge", macro.getApi().ageSeconds());
        m.put("flips", macro.flipsCompleted);
        m.put("buysFilled", macro.buysFilled);
        m.put("sellsFilled", macro.sellsFilled);
        m.put("ordersPlaced", macro.ordersPlaced);
        m.put("top", macro.topCandidate.replaceAll("§.", ""));

        List<Map<String, Object>> orders = new ArrayList<>();
        for (ParsedOrder o : macro.lastOrders) {
            Map<String, Object> om = new HashMap<>();
            om.put("side", o.buy() ? "BUY" : "SELL");
            om.put("item", o.item());
            om.put("price", o.pricePerUnit());
            om.put("amount", o.amount());
            om.put("filled", o.filledPct());
            orders.add(om);
        }
        m.put("orders", orders);

        List<Map<String, Object>> flips = new ArrayList<>();
        for (FlipCandidate c : macro.getApi().getCandidates().stream().limit(8).toList()) {
            Map<String, Object> fm = new HashMap<>();
            fm.put("name", c.displayName);
            fm.put("margin", c.margin(config.taxFraction));
            fm.put("vol", c.minWeeklyVolume());
            fm.put("trend", c.trend);
            fm.put("sigma", c.volatility);
            flips.add(fm);
        }
        m.put("topFlips", flips);
        m.put("benched", macro.benchedList());
        m.put("log", new ArrayList<>(macro.logLines));
        synchronized (history) { m.put("history", new ArrayList<>(history)); }
        return GSON.toJson(m);
    }

    private static void respond(HttpExchange ex, int code, String type, String body) {
        try {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ UI --
    private static final String PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BZ Flipper</title>
<style>
:root{--bg:#0a0c10;--panel:#14171d;--panel2:#1b1f27;--line:#262b35;--tx:#e8ebf0;
 --dim:#8b93a1;--green:#22e07c;--red:#ff5470;--cyan:#38d1ff;--gold:#ffb84d}
*{margin:0;padding:0;box-sizing:border-box;font-family:'Segoe UI',system-ui,sans-serif}
body{background:radial-gradient(1200px 600px at 70% -10%,#151a24 0%,var(--bg) 55%);color:var(--tx);min-height:100vh;padding:22px}
.wrap{max-width:1080px;margin:0 auto}
header{display:flex;align-items:center;gap:14px;padding:6px 4px 18px}
.logo{width:40px;height:40px;border-radius:10px;background:linear-gradient(135deg,#2b3342,#171b23);
 display:flex;align-items:center;justify-content:center;font-size:20px;border:1px solid var(--line)}
h1{font-size:17px;letter-spacing:.4px}
.dot{width:9px;height:9px;border-radius:50%;background:var(--red);box-shadow:0 0 10px var(--red)}
.dot.on{background:var(--green);box-shadow:0 0 12px var(--green)}
.badge{font-size:11px;color:var(--gold);border:1px solid #4a3d1f;background:#241d0d;padding:3px 9px;border-radius:99px;display:none}
.tabs{display:flex;gap:8px;margin-bottom:16px}
.tab{flex:1;text-align:center;padding:11px 0;border-radius:12px;background:var(--panel);border:1px solid var(--line);
 color:var(--dim);font-weight:600;font-size:13px;cursor:pointer;transition:.15s}
.tab:hover{color:var(--tx)}
.tab.act{background:#fff;color:#0a0c10;border-color:#fff}
.page{display:none}.page.act{display:block}
.grid{display:grid;grid-template-columns:1fr 300px;gap:16px}
@media(max-width:900px){.grid{grid-template-columns:1fr}}
.card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:16px}
.stat{padding:13px 16px;margin-bottom:12px;text-align:center}
.stat .k{font-size:10.5px;letter-spacing:1.6px;color:var(--dim);font-weight:700}
.stat .v{font-size:23px;font-weight:800;margin-top:3px}
.g{color:var(--green);text-shadow:0 0 18px rgba(34,224,124,.35)}
.c{color:var(--cyan)} .r{color:var(--red)}
canvas{width:100%;height:380px;display:block}
table{width:100%;border-collapse:collapse;font-size:13px}
th{color:var(--dim);font-size:10.5px;letter-spacing:1.2px;text-align:left;padding:8px 10px;border-bottom:1px solid var(--line)}
td{padding:9px 10px;border-bottom:1px solid #1d222b}
.side{font-weight:800;font-size:11px;padding:2px 8px;border-radius:6px}
.side.B{background:#0d2b1b;color:var(--green)} .side.S{background:#2b230d;color:var(--gold)}
.bar{height:6px;border-radius:99px;background:#242a35;overflow:hidden;min-width:90px}
.bar i{display:block;height:100%;border-radius:99px;background:linear-gradient(90deg,var(--cyan),var(--green))}
#log{font-family:Consolas,monospace;font-size:12px;line-height:1.8;color:#b9c2cf;height:520px;overflow-y:auto;white-space:pre-wrap}
#log::-webkit-scrollbar{width:8px}#log::-webkit-scrollbar-thumb{background:#2a3140;border-radius:99px}
.btn{width:100%;padding:15px;border:0;border-radius:13px;font-size:14px;font-weight:800;cursor:pointer;margin-bottom:12px;transition:.15s}
.btn:hover{filter:brightness(1.15)}
.btn.start{background:linear-gradient(135deg,#1d9e5c,#22e07c);color:#04180c}
.btn.stop{background:linear-gradient(135deg,#8f2437,#ff5470);color:#fff}
.btn.ghost{background:var(--panel2);color:var(--tx);border:1px solid var(--line)}
.kv{display:flex;justify-content:space-between;padding:7px 2px;font-size:13px;border-bottom:1px solid #1d222b}
.kv b{color:var(--dim);font-weight:600}
h3{font-size:11px;letter-spacing:1.6px;color:var(--dim);margin:4px 0 10px}
.flip{display:flex;justify-content:space-between;padding:7px 2px;font-size:12.5px;border-bottom:1px solid #1d222b}
</style></head><body><div class="wrap">
<header>
 <div class="logo">🍪</div><h1>BZ FLIPPER</h1>
 <div class="dot" id="dot"></div><span id="stateTx" style="color:var(--dim);font-size:12px"></span>
 <span class="badge" id="dry">DRY RUN</span>
 <span style="margin-left:auto;color:var(--dim);font-size:12px" id="apiAge"></span>
</header>
<div class="tabs">
 <div class="tab" data-p="chat">Chat</div>
 <div class="tab" data-p="orders">Orders</div>
 <div class="tab act" data-p="stats">Stats</div>
 <div class="tab" data-p="control">Control</div>
</div>

<div class="page act" id="p-stats"><div class="grid">
 <div class="card"><canvas id="chart" width="1400" height="760"></canvas></div>
 <div>
  <div class="card stat"><div class="k">PROFIT</div><div class="v g" id="sProfit">—</div></div>
  <div class="card stat"><div class="k">P/H NOW · SESSION</div><div class="v c" id="sRate">—</div></div>
  <div class="card stat"><div class="k">IN OPEN OFFERS</div><div class="v g" id="sProj">—</div></div>
  <div class="card stat"><div class="k">UPTIME</div><div class="v" id="sUp">—</div></div>
  <div class="card stat"><div class="k">COOKIE</div><div class="v" id="sCookie">—</div></div>
  <div class="card stat"><div class="k">PURSE</div><div class="v" id="sPurse">—</div></div>
 </div>
</div></div>

<div class="page" id="p-orders">
 <div class="card"><table id="ordTable">
  <thead><tr><th>SIDE</th><th>ITEM</th><th>PRICE</th><th>AMOUNT</th><th>FILLED</th><th style="width:120px"></th></tr></thead>
  <tbody></tbody></table>
  <div id="noOrd" style="color:var(--dim);text-align:center;padding:26px;display:none">no open orders</div>
 </div>
 <div class="card" style="margin-top:16px"><h3>TOP FLIP CANDIDATES</h3><div id="flips"></div></div>
</div>

<div class="page" id="p-chat"><div class="card"><div id="log"></div></div></div>

<div class="page" id="p-control"><div class="grid">
 <div class="card">
  <button class="btn start" id="bToggle" onclick="post('toggle')">START</button>
  <button class="btn stop" onclick="post('panic')">⛔ PANIC STOP</button>
  <button class="btn ghost" id="bDry" onclick="post('dryrun')">Toggle Dry Run</button>
 </div>
 <div>
  <div class="card"><h3>STATE</h3>
   <div class="kv"><b>phase</b><span id="cPhase">—</span></div>
   <div class="kv"><b>status</b><span id="cStatus" style="max-width:170px;text-align:right">—</span></div>
   <div class="kv"><b>flips</b><span id="cFlips">—</span></div>
   <div class="kv"><b>fills B/S</b><span id="cFills">—</span></div>
   <div class="kv"><b>all-time</b><span id="cAll" class="g">—</span></div>
  </div>
  <div class="card" style="margin-top:14px"><h3>BENCHED ITEMS</h3><div id="bench" style="font-size:12.5px;color:var(--dim)"></div></div>
 </div>
</div></div>
</div>
<script>
const $=id=>document.getElementById(id);
document.querySelectorAll('.tab').forEach(t=>t.onclick=()=>{
 document.querySelectorAll('.tab').forEach(x=>x.classList.remove('act'));
 document.querySelectorAll('.page').forEach(x=>x.classList.remove('act'));
 t.classList.add('act'); $('p-'+t.dataset.p).classList.add('act');});
function fmt(n){if(n==null||isNaN(n))return '—';const a=Math.abs(n);
 if(a>=1e9)return (n/1e9).toFixed(2)+'B';if(a>=1e6)return (n/1e6).toFixed(2)+'M';
 if(a>=1e3)return (n/1e3).toFixed(1)+'k';return Math.round(n)+''}
function dur(s){const h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=Math.floor(s%60);
 return h+'H '+m+'M '+x+'S'}
function post(p){fetch('/api/'+p,{method:'POST'}).then(()=>setTimeout(load,300))}
let HIST=[];
function draw(){
 const cv=$('chart'),ctx=cv.getContext('2d'),W=cv.width,H=cv.height;
 ctx.clearRect(0,0,W,H);
 const P={l:90,r:24,t:24,b:64};
 const xs=HIST.map(h=>h[0]),ys=HIST.map(h=>h[1]);
 let lo=Math.min(0,...ys),hi=Math.max(1000,...ys);const pad=(hi-lo)*0.08;lo-=pad;hi+=pad;
 const X=t=>P.l+(W-P.l-P.r)*(t-xs[0])/Math.max(1,xs[xs.length-1]-xs[0]);
 const Y=v=>P.t+(H-P.t-P.b)*(1-(v-lo)/(hi-lo));
 ctx.strokeStyle='#1e232d';ctx.fillStyle='#69707e';ctx.font='20px Segoe UI';ctx.lineWidth=1;
 for(let i=0;i<=6;i++){const v=lo+(hi-lo)*i/6,y=Y(v);
  ctx.beginPath();ctx.moveTo(P.l,y);ctx.lineTo(W-P.r,y);ctx.stroke();
  ctx.textAlign='right';ctx.fillText(fmt(v),P.l-10,y+6);}
 if(HIST.length>1){
  const n=5;ctx.textAlign='center';
  for(let i=0;i<=n;i++){const t=xs[0]+(xs[xs.length-1]-xs[0])*i/n;
   const d=new Date(t);ctx.fillText(d.getHours().toString().padStart(2,'0')+':'+d.getMinutes().toString().padStart(2,'0'),X(t),H-26);}
  const grad=ctx.createLinearGradient(0,P.t,0,H-P.b);
  grad.addColorStop(0,'rgba(34,224,124,.30)');grad.addColorStop(1,'rgba(34,224,124,.02)');
  ctx.beginPath();ctx.moveTo(X(xs[0]),Y(ys[0]));
  for(let i=1;i<HIST.length;i++)ctx.lineTo(X(xs[i]),Y(ys[i]));
  ctx.lineTo(X(xs[xs.length-1]),Y(lo)+0);ctx.lineTo(X(xs[0]),Y(lo));ctx.closePath();
  ctx.fillStyle=grad;ctx.fill();
  ctx.beginPath();ctx.moveTo(X(xs[0]),Y(ys[0]));
  for(let i=1;i<HIST.length;i++)ctx.lineTo(X(xs[i]),Y(ys[i]));
  ctx.strokeStyle='#22e07c';ctx.lineWidth=3.5;ctx.lineJoin='round';ctx.stroke();
  const lx=X(xs[xs.length-1]),ly=Y(ys[ys.length-1]);
  ctx.beginPath();ctx.arc(lx,ly,7,0,7);ctx.fillStyle='#22e07c';ctx.fill();
 }else{ctx.textAlign='center';ctx.fillText('collecting data…',W/2,H/2);}
}
function load(){fetch('/api/stats').then(r=>r.json()).then(d=>{
 $('dot').classList.toggle('on',d.enabled);
 $('stateTx').textContent=d.enabled?'RUNNING':'STOPPED';
 $('dry').style.display=d.dryRun?'inline-block':'none';
 $('apiAge').textContent='api '+d.apiAge+'s';
 $('sProfit').textContent=fmt(d.profit);
 $('sRate').textContent=fmt(d.perHourNow)+' · '+fmt(d.perHourSession);
 $('sProj').textContent='+'+fmt(d.projected);
 $('sUp').textContent=dur(d.uptimeSec);
 $('sCookie').textContent=d.cookie;
 $('sPurse').textContent=fmt(d.purse);
 $('cPhase').textContent=d.phase;$('cStatus').textContent=d.status;
 $('cFlips').textContent=d.flips;$('cFills').textContent=d.buysFilled+' / '+d.sellsFilled;
 $('cAll').textContent=fmt(d.allTime);
 $('bToggle').textContent=d.enabled?'STOP':'START';
 $('bToggle').className='btn '+(d.enabled?'stop':'start');
 const tb=document.querySelector('#ordTable tbody');tb.innerHTML='';
 $('noOrd').style.display=d.orders.length?'none':'block';
 d.orders.forEach(o=>{const tr=document.createElement('tr');
  tr.innerHTML='<td><span class="side '+(o.side==='BUY'?'B':'S')+'">'+o.side+'</span></td>'+
   '<td>'+o.item+'</td><td>'+fmt(o.price)+'</td><td>'+fmt(o.amount)+'</td>'+
   '<td>'+o.filled.toFixed(1)+'%</td><td><div class="bar"><i style="width:'+Math.min(100,o.filled)+'%"></i></div></td>';
  tb.appendChild(tr);});
 $('flips').innerHTML=d.topFlips.map(f=>'<div class="flip"><span>'+f.name+'</span><span>'+
  '<span class="c">'+(f.margin*100).toFixed(1)+'%</span> · σ'+(f.sigma*100).toFixed(1)+'% · '+
  '<span class="'+(f.trend>=0?'g':'r')+'">'+(f.trend>=0?'↑':'↓')+(Math.abs(f.trend)*100).toFixed(1)+'%</span></span></div>').join('');
 $('bench').innerHTML=d.benched.length?d.benched.join('<br>'):'none';
 const lg=$('log');const atBottom=lg.scrollTop+lg.clientHeight>=lg.scrollHeight-30;
 lg.textContent=d.log.join(String.fromCharCode(10));
 if(atBottom)lg.scrollTop=lg.scrollHeight;
 HIST=d.history.map(h=>[h[0],h[1]]);draw();
}).catch(()=>{$('stateTx').textContent='disconnected';$('dot').classList.remove('on');});}
load();setInterval(load,1500);
</script></body></html>
""";
}
