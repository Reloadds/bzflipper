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
            server.createContext("/report", ex -> respond(ex, 200, "text/plain", macro.buildReport()));
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
        m.put("capture", macro.captureEstimate());   // learned account share (or config guess)
        m.put("deployed", macro.deployedBuyCapital());
        m.put("utilization", macro.utilization());
        m.put("metaAvoided", macro.metaAvoidedCount());   // learned avoid-list size
        m.put("minMargin", macro.getApi().effectiveMinMargin());   // live adaptive margin gate
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
            om.put("rate", o.buy() ? macro.measuredBuyRate(o.item())
                                   : macro.measuredSellRate(o.item()));   // measured units/hr (0 = unmeasured)
            orders.add(om);
        }
        m.put("orders", orders);

        // The picker's ACTUAL objective per candidate (same code path as
        // pickNextItem) — this is how ranking changes are verified in dry-run.
        List<Map<String, Object>> rank = new ArrayList<>();
        for (BazaarMacro.RankRow r : macro.ranking.stream().limit(12).toList()) {
            Map<String, Object> rm = new HashMap<>();
            rm.put("name", r.item());
            rm.put("cph", r.cph());
            rm.put("ppu", r.ppu());
            rm.put("buyRate", r.buyRate());
            rm.put("buyMeasured", r.buyMeasured());
            rm.put("sellRate", r.sellRate());
            rm.put("sellMeasured", r.sellMeasured());
            rm.put("velocity", r.velocity());
            rm.put("eff", r.eff());
            rm.put("state", r.state());
            rank.add(rm);
        }
        m.put("ranking", rank);

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
<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Bazaar Flipper</title>
<style>
:root{
 --bg:#0c0d0f;--panel:#121316;--panel2:#17181c;--line:#212227;--line2:#1a1b1f;
 --tx:#e9eaec;--dim:#969aa2;--faint:#63666e;
 --green:#40c463;--green2:#2ea043;--red:#e5534b;--blue:#5a9bff}
*{margin:0;padding:0;box-sizing:border-box}
html{-webkit-font-smoothing:antialiased}
body{background:var(--bg);color:var(--tx);min-height:100vh;padding:28px 24px;
 font-family:'Inter',-apple-system,'Segoe UI',system-ui,sans-serif;font-size:14px;line-height:1.5}
.num{font-variant-numeric:tabular-nums;font-feature-settings:'tnum'}
.wrap{max-width:1120px;margin:0 auto}
header{display:flex;align-items:center;gap:12px;padding-bottom:22px}
.mark{width:30px;height:30px;border-radius:8px;border:1px solid var(--line);background:var(--panel2);
 display:flex;align-items:center;justify-content:center;font-size:12px;font-weight:700;color:var(--dim);letter-spacing:.5px}
h1{font-size:15px;font-weight:600;letter-spacing:-.1px}
.stat-dot{width:6px;height:6px;border-radius:50%;background:var(--faint);margin-left:2px}
.stat-dot.on{background:var(--green)}
.state{font-size:12px;color:var(--dim)}
.pill{font-size:11px;font-weight:600;color:#e3b341;border:1px solid #3a3115;background:#1c1808;
 padding:2px 8px;border-radius:5px;display:none;letter-spacing:.2px}
.right{margin-left:auto;font-size:12px;color:var(--faint)}
.tabs{display:flex;gap:2px;border-bottom:1px solid var(--line);margin-bottom:22px}
.tab{padding:9px 15px;font-size:13px;font-weight:500;color:var(--dim);cursor:pointer;
 border-bottom:1.5px solid transparent;margin-bottom:-1px;transition:color .12s}
.tab:hover{color:var(--tx)}
.tab.act{color:var(--tx);border-bottom-color:var(--tx)}
.page{display:none}.page.act{display:block}
.grid{display:grid;grid-template-columns:1fr 268px;gap:14px;align-items:start}
@media(max-width:880px){.grid{grid-template-columns:1fr}}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px}
.pad{padding:16px}
.chart-card{padding:14px 16px 8px}
canvas{width:100%;height:360px;display:block}
.tiles{display:flex;flex-direction:column;gap:1px;background:var(--line);border:1px solid var(--line);border-radius:10px;overflow:hidden}
.tile{background:var(--panel);padding:13px 15px}
.tile .k{font-size:11px;color:var(--dim);font-weight:500;letter-spacing:.2px}
.tile .v{font-size:20px;font-weight:650;margin-top:2px;letter-spacing:-.4px}
.g{color:var(--green)} .r{color:var(--red)} .sub{color:var(--faint);font-size:13px;font-weight:500}
table{width:100%;border-collapse:collapse;font-size:13px}
th{color:var(--faint);font-size:11px;font-weight:600;text-align:left;padding:11px 14px;border-bottom:1px solid var(--line)}
th.n,td.n{text-align:right}
td{padding:11px 14px;border-bottom:1px solid var(--line2);color:var(--tx)}
tr:last-child td{border-bottom:0}
.side{font-size:11px;font-weight:600;letter-spacing:.3px}
.side.B{color:var(--green)} .side.S{color:#e3b341}
.bar{height:4px;border-radius:99px;background:#23242a;overflow:hidden;width:100%}
.bar i{display:block;height:100%;border-radius:99px;background:var(--green)}
.empty{color:var(--faint);text-align:center;padding:34px;font-size:13px}
#log{font-family:'SF Mono','JetBrains Mono',ui-monospace,Consolas,monospace;font-size:12px;
 line-height:1.85;color:#b3b8c0;height:540px;overflow-y:auto;white-space:pre-wrap;padding:16px}
#log::-webkit-scrollbar{width:10px}#log::-webkit-scrollbar-thumb{background:#25262b;border-radius:99px;border:3px solid var(--panel)}
.btn{width:100%;padding:12px;border:1px solid transparent;border-radius:8px;font-size:13px;font-weight:600;
 cursor:pointer;margin-bottom:10px;transition:.12s;font-family:inherit}
.btn.start{background:var(--green2);color:#04140a;border-color:var(--green2)}
.btn.start:hover{background:var(--green)}
.btn.stop{background:transparent;color:var(--red);border-color:#3a2422}
.btn.stop:hover{background:#1c1210}
.btn.ghost{background:var(--panel2);color:var(--dim);border-color:var(--line)}
.btn.ghost:hover{color:var(--tx);border-color:#2c2d33}
.kv{display:flex;justify-content:space-between;align-items:center;padding:10px 0;font-size:13px;border-bottom:1px solid var(--line2)}
.kv:last-child{border-bottom:0}.kv b{color:var(--dim);font-weight:500}
.h3{font-size:11px;font-weight:600;color:var(--faint);letter-spacing:.3px;padding:14px 16px 0;text-transform:uppercase}
.flip{display:flex;justify-content:space-between;align-items:center;padding:10px 16px;font-size:13px;border-bottom:1px solid var(--line2)}
.flip:last-child{border-bottom:0}.flip .mut{color:var(--faint);font-size:12px}
.mono{font-family:ui-monospace,Consolas,monospace}
</style></head><body><div class="wrap">
<header>
 <div class="mark">bz</div>
 <h1>Bazaar Flipper</h1>
 <span class="stat-dot" id="dot"></span><span class="state" id="stateTx"></span>
 <span class="pill" id="dry">DRY&nbsp;RUN</span>
 <span class="right num" id="apiAge"></span>
</header>
<div class="tabs">
 <div class="tab act" data-p="stats">Overview</div>
 <div class="tab" data-p="orders">Orders</div>
 <div class="tab" data-p="chat">Activity</div>
 <div class="tab" data-p="report">Report</div>
 <div class="tab" data-p="control">Control</div>
</div>

<div class="page act" id="p-stats"><div class="grid">
 <div class="card chart-card"><canvas id="chart" width="1360" height="720"></canvas></div>
 <div class="tiles">
  <div class="tile"><div class="k">Session profit</div><div class="v g num" id="sProfit">—</div></div>
  <div class="tile"><div class="k">Per hour · session</div><div class="v num" id="sRate">—</div></div>
  <div class="tile"><div class="k">In open offers</div><div class="v g num" id="sProj">—</div></div>
  <div class="tile"><div class="k">Uptime</div><div class="v num" id="sUp">—</div></div>
  <div class="tile"><div class="k">Cookie buff</div><div class="v num" id="sCookie">—</div></div>
  <div class="tile"><div class="k">Purse</div><div class="v num" id="sPurse">—</div></div>
 </div>
</div></div>

<div class="page" id="p-orders">
 <div class="card"><table id="ordTable">
  <thead><tr><th>Side</th><th>Item</th><th class="n">Price</th><th class="n">Amount</th><th class="n">Filled</th><th style="width:110px"></th></tr></thead>
  <tbody></tbody></table>
  <div class="empty" id="noOrd" style="display:none">No open orders</div>
 </div>
 <div class="card" style="margin-top:14px"><div class="h3">Top candidates</div><div id="flips"></div></div>
</div>

<div class="page" id="p-chat"><div class="card"><div id="log"></div></div></div>

<div class="page" id="p-report"><div class="card"><pre id="report" style="font-family:'SF Mono','JetBrains Mono',ui-monospace,Consolas,monospace;font-size:12px;line-height:1.7;color:#c8ccd4;padding:16px;overflow-x:auto;white-space:pre;max-height:640px;overflow-y:auto">Loading…</pre></div></div>

<div class="page" id="p-control"><div class="grid">
 <div class="card pad">
  <button class="btn start" id="bToggle" onclick="post('toggle')">Start</button>
  <button class="btn stop" onclick="post('panic')">Panic stop</button>
  <button class="btn ghost" id="bDry" onclick="post('dryrun')">Toggle dry run</button>
 </div>
 <div>
  <div class="card pad">
   <div class="kv"><b>Phase</b><span id="cPhase" class="mono">—</span></div>
   <div class="kv"><b>Status</b><span id="cStatus" style="max-width:160px;text-align:right;color:var(--dim)">—</span></div>
   <div class="kv"><b>Flips</b><span id="cFlips" class="num">—</span></div>
   <div class="kv"><b>Fills B / S</b><span id="cFills" class="num">—</span></div>
   <div class="kv"><b>All-time</b><span id="cAll" class="g num">—</span></div>
  </div>
  <div class="card" style="margin-top:14px"><div class="h3">Benched</div><div id="bench" style="padding:8px 16px 14px;font-size:13px;color:var(--dim)"></div></div>
 </div>
</div></div>
</div>
<script>
const $=id=>document.getElementById(id);
document.querySelectorAll('.tab').forEach(t=>t.onclick=()=>{
 document.querySelectorAll('.tab').forEach(x=>x.classList.remove('act'));
 document.querySelectorAll('.page').forEach(x=>x.classList.remove('act'));
 t.classList.add('act'); $('p-'+t.dataset.p).classList.add('act');
 if(t.dataset.p==='report')loadReport();});
function loadReport(){fetch('/report').then(r=>r.text()).then(x=>{$('report').textContent=x;});}
function fmt(n){if(n==null||isNaN(n))return '—';const a=Math.abs(n);
 if(a>=1e9)return (n/1e9).toFixed(2)+'B';if(a>=1e6)return (n/1e6).toFixed(2)+'M';
 if(a>=1e3)return (n/1e3).toFixed(1)+'k';return Math.round(n)+''}
function dur(s){const h=Math.floor(s/3600),m=Math.floor(s%3600/60);
 return h+'h '+String(m).padStart(2,'0')+'m'}
function post(p){fetch('/api/'+p,{method:'POST'}).then(()=>setTimeout(load,300))}
let HIST=[];
function draw(){
 const cv=$('chart'),ctx=cv.getContext('2d'),W=cv.width,H=cv.height;
 ctx.clearRect(0,0,W,H);
 const P={l:78,r:22,t:20,b:52};
 const xs=HIST.map(h=>h[0]),ys=HIST.map(h=>h[1]);
 let lo=Math.min(0,...ys),hi=Math.max(1000,...ys);const pad=(hi-lo)*0.1||1;lo-=pad;hi+=pad;
 const X=t=>P.l+(W-P.l-P.r)*(t-xs[0])/Math.max(1,xs[xs.length-1]-xs[0]);
 const Y=v=>P.t+(H-P.t-P.b)*(1-(v-lo)/(hi-lo));
 ctx.font='500 18px Inter,Segoe UI,sans-serif';ctx.lineWidth=1;
 for(let i=0;i<=5;i++){const v=lo+(hi-lo)*i/5,y=Y(v);
  ctx.strokeStyle='#191a1e';ctx.beginPath();ctx.moveTo(P.l,y);ctx.lineTo(W-P.r,y);ctx.stroke();
  ctx.fillStyle='#5b5e66';ctx.textAlign='right';ctx.fillText(fmt(v),P.l-12,y+6);}
 if(HIST.length>1){
  const n=5;ctx.textAlign='center';ctx.fillStyle='#5b5e66';
  for(let i=0;i<=n;i++){const t=xs[0]+(xs[xs.length-1]-xs[0])*i/n;const d=new Date(t);
   ctx.fillText(d.getHours().toString().padStart(2,'0')+':'+d.getMinutes().toString().padStart(2,'0'),X(t),H-22);}
  const grad=ctx.createLinearGradient(0,P.t,0,H-P.b);
  grad.addColorStop(0,'rgba(64,196,99,.14)');grad.addColorStop(1,'rgba(64,196,99,0)');
  ctx.beginPath();ctx.moveTo(X(xs[0]),Y(ys[0]));
  for(let i=1;i<HIST.length;i++)ctx.lineTo(X(xs[i]),Y(ys[i]));
  ctx.lineTo(X(xs[xs.length-1]),H-P.b);ctx.lineTo(X(xs[0]),H-P.b);ctx.closePath();
  ctx.fillStyle=grad;ctx.fill();
  ctx.beginPath();ctx.moveTo(X(xs[0]),Y(ys[0]));
  for(let i=1;i<HIST.length;i++)ctx.lineTo(X(xs[i]),Y(ys[i]));
  ctx.strokeStyle='#40c463';ctx.lineWidth=2;ctx.lineJoin='round';ctx.stroke();
  const lx=X(xs[xs.length-1]),ly=Y(ys[ys.length-1]);
  ctx.beginPath();ctx.arc(lx,ly,3.2,0,7);ctx.fillStyle='#40c463';ctx.fill();
 }else{ctx.textAlign='center';ctx.fillStyle='#4a4d55';ctx.fillText('Collecting data',W/2,H/2);}
}
function load(){fetch('/api/stats').then(r=>r.json()).then(d=>{
 $('dot').classList.toggle('on',d.enabled);
 $('stateTx').textContent=d.enabled?'Running':'Stopped';
 $('dry').style.display=d.dryRun?'inline-block':'none';
 $('apiAge').textContent='updated '+d.apiAge+'s ago';
 $('sProfit').textContent=fmt(d.profit);
 $('sRate').innerHTML=fmt(d.perHourNow)+' <span class="sub">/ '+fmt(d.perHourSession)+'</span>';
 $('sProj').textContent='+'+fmt(d.projected);
 $('sUp').textContent=dur(d.uptimeSec);
 $('sCookie').textContent=d.cookie;
 $('sPurse').textContent=fmt(d.purse);
 $('cPhase').textContent=d.phase;$('cStatus').textContent=d.status;
 $('cFlips').textContent=d.flips;$('cFills').textContent=d.buysFilled+' / '+d.sellsFilled;
 $('cAll').textContent=fmt(d.allTime);
 $('bToggle').textContent=d.enabled?'Stop':'Start';
 $('bToggle').className='btn '+(d.enabled?'stop':'start');
 const tb=document.querySelector('#ordTable tbody');tb.innerHTML='';
 $('noOrd').style.display=d.orders.length?'none':'block';
 d.orders.forEach(o=>{const tr=document.createElement('tr');
  tr.innerHTML='<td><span class="side '+(o.side==='BUY'?'B':'S')+'">'+o.side+'</span></td>'+
   '<td>'+o.item+(o.rate>0?' <span style="color:var(--faint);font-size:12px">✓'+fmt(o.rate)+'u/hr</span>':'')+'</td>'+
   '<td class="n num">'+fmt(o.price)+'</td><td class="n num">'+fmt(o.amount)+'</td>'+
   '<td class="n num">'+o.filled.toFixed(0)+'%</td><td><div class="bar"><i style="width:'+Math.min(100,o.filled)+'%"></i></div></td>';
  tb.appendChild(tr);});
 // Ranking = the picker's real objective (cph), with BOTH leg rates feeding it
 // (B = our buy order fills, S = our sell offer fills; velocity = series flow).
 // ✓ = rate MEASURED from our own fills; ~ = volume-based estimate.
 $('flips').innerHTML=((d.ranking&&d.ranking.length)?d.ranking.map(f=>
  '<div class="flip"><span>'+f.name+(f.state!=='ok'?' <span class="mut">·'+f.state+'</span>':'')+'</span><span class="mut">'+
  '<span class="g">'+fmt(f.cph)+'/hr</span> · B'+(f.buyMeasured?'✓':'~')+fmt(f.buyRate)+' S'+(f.sellMeasured?'✓':'~')+fmt(f.sellRate)+
  ' → v'+fmt(f.velocity)+'u/hr · eff '+f.eff.toFixed(2)+
  '</span></div>').join(''):d.topFlips.map(f=>'<div class="flip"><span>'+f.name+'</span><span class="mut">'+
  '<span class="g">'+(f.margin*100).toFixed(1)+'%</span> · σ'+(f.sigma*100).toFixed(1)+' · '+
  '<span class="'+(f.trend>=0?'g':'r')+'">'+(f.trend>=0?'▲':'▼')+(Math.abs(f.trend)*100).toFixed(1)+'</span></span></div>').join(''))||'<div class="empty">Loading</div>';
 $('bench').innerHTML=d.benched.length?d.benched.join('<br>'):'None';
 const lg=$('log');const atBottom=lg.scrollTop+lg.clientHeight>=lg.scrollHeight-30;
 lg.textContent=d.log.join(String.fromCharCode(10));
 if(atBottom)lg.scrollTop=lg.scrollHeight;
 HIST=d.history.map(h=>[h[0],h[1]]);draw();
}).catch(()=>{$('stateTx').textContent='Disconnected';$('dot').classList.remove('on');});}
load();setInterval(load,1500);
setInterval(()=>{const rt=document.querySelector('.tab[data-p="report"]');if(rt&&rt.classList.contains('act'))loadReport();},4000);
</script></body></html>
""";
}
