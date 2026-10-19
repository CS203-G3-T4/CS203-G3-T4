const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const fs=require('node:fs');
class Element {
  constructor(tag){this.tag=tag;this.attrs={};this.children=[];}
  setAttribute(k,v){this.attrs[k]=v;}
  replaceChildren(){this.children=[];}
  appendChild(n){this.children.push(n);}
}
test('live chart preserves gaps, negative prices, spikes and Singapore midnight labels',()=>{
  const svg=new Element('svg');
  const context=vm.createContext({document:{createElementNS:(_ns,tag)=>new Element(tag)},Date,Number,Math});
  vm.runInContext(fs.readFileSync('src/main/resources/static/js/forecast-chart.js','utf8'),context);
  context.svg=svg;
  vm.runInContext(`WattlyPriceChart.draw(svg,[{name:'Forecast',color:'blue',points:[
    {time:'2026-10-17T15:30:00Z',value:-5},
    {time:'2026-10-17T16:00:00Z',value:0,spike:true},
    {time:'2026-10-17T17:00:00Z',value:20}]}]);`,context);
  const path=svg.children.find(n=>n.tag==='path');
  assert.equal((path.attrs.d.match(/M/g)||[]).length,2);
  assert.equal((path.attrs.d.match(/L/g)||[]).length,1);
  assert.equal(svg.children.filter(n=>n.tag==='circle').length,3);
  assert.equal(svg.children.filter(n=>n.tag==='circle'&&n.attrs.r===5).length,1);
  assert.ok(svg.children.some(n=>n.tag==='text'&&String(n.textContent).includes('-5.0')));
  assert.ok(svg.children.some(n=>n.tag==='desc'&&n.textContent.includes('Exact values')));
  vm.runInContext('WattlyPriceChart.draw(svg,[])',context);
  assert.equal(svg.children.length,0);
});
