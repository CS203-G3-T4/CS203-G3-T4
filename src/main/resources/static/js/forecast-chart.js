// Native SVG; missing half-hours break the line. Tables contain the exact values.
const WattlyPriceChart = {
  draw(svg,series) {
    svg.replaceChildren();
    const all=series.flatMap(s=>s.points).filter(p=>Number.isFinite(p.value)&&Number.isFinite(Date.parse(p.time)));
    if (!all.length) return;
    const ns='http://www.w3.org/2000/svg';
    function node(tag,attrs,text) { const n=document.createElementNS(ns,tag); Object.entries(attrs).forEach(([k,v])=>n.setAttribute(k,v)); if(text!==undefined)n.textContent=text; svg.appendChild(n); return n; }
    const start=Math.min(...all.map(p=>Date.parse(p.time))),end=Math.max(start+1800000,...all.map(p=>Date.parse(p.time)));
    const lo=Math.min(0,...all.map(p=>p.value)),hi=Math.max(lo+1,...all.map(p=>p.value));
    const x=t=>70+(Date.parse(t)-start)/(end-start)*850, y=v=>250-(v-lo)/(hi-lo)*210;
    node('title',{},'Actual and forecast wholesale prices, SGD/MWh');
    node('desc',{},'Singapore times. Gaps break the line; red circles mark expected spikes. Exact values appear in the tables.');
    node('line',{x1:70,x2:920,y1:y(0),y2:y(0),stroke:'#999'});
    for(let i=0;i<=4;i++) { const v=lo+(hi-lo)*i/4; node('text',{x:5,y:y(v),fill:'currentColor','font-size':12},v.toFixed(1)); }
    for(let i=0;i<=4;i++) { const t=start+(end-start)*i/4;
      node('text',{x:70+i*212.5,y:278,fill:'currentColor','font-size':11,'text-anchor':'middle'},new Date(t).toLocaleString('en-SG',{timeZone:'Asia/Singapore',day:'2-digit',month:'short',hour:'2-digit',minute:'2-digit',hour12:false})); }
    series.forEach((s,index)=>{
      let last=null,path='';
      s.points.slice().sort((a,b)=>Date.parse(a.time)-Date.parse(b.time)).forEach(p=>{
        if(!Number.isFinite(p.value)) {last=null;return;}
        const t=Date.parse(p.time); if(!Number.isFinite(t))return;
        path+=(last!==null&&t-last===1800000?'L':'M')+x(p.time)+','+y(p.value)+' '; last=t;
        node('circle',{cx:x(p.time),cy:y(p.value),r:p.spike?5:2,fill:p.spike?'#b42318':s.color});
      });
      node('path',{d:path,fill:'none',stroke:s.color,'stroke-width':2,'data-series':s.name});
      node('text',{x:80+index*230,y:20,fill:s.color,'font-size':14},s.name);
    });
  }
};
