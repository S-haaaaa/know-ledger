'use strict';
window.AiChatUI=(()=>{
 let state=null,mode='chat',selectedProvider='siliconflow',clearKeyPending=false,testStatus=null;
 const dialog=()=>document.getElementById('ai-chat-dialog');
 const bodyEl=()=>document.getElementById('ai-chat-body');
 const composerEl=()=>document.getElementById('ai-chat-composer');
 const subEl=()=>document.getElementById('ai-chat-sub');
 const esc=v=>typeof escapeText==='function'?escapeText(v):String(v??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

 function loadState(){
  if(!native?.aiChatState)return state||{provider:'siliconflow',baseUrl:'https://api.siliconflow.cn/v1',model:'deepseek-ai/DeepSeek-V4.1-Flash',hasApiKey:false,maskedApiKey:'',includeSummary:true,busy:false,providers:[{provider:'siliconflow',label:'硅基流动 (SiliconFlow)',baseUrl:'https://api.siliconflow.cn/v1',model:'deepseek-ai/DeepSeek-V4.1-Flash',hasApiKey:false,maskedApiKey:'',suggestedModels:['deepseek-ai/DeepSeek-V4.1-Flash','deepseek-ai/DeepSeek-V4-Pro','Qwen/Qwen3.8-27B','zai-org/GLM-5.3-Flash']},{provider:'aliyun',label:'阿里云百炼 (DashScope)',baseUrl:'https://dashscope.aliyuncs.com/compatible-mode/v1',model:'qwen3.8-flash',hasApiKey:false,maskedApiKey:'',suggestedModels:['qwen3.8-flash','qwen3.7-plus','qwen4-max','qwen3.8-omni-flash','deepseek-v4.1-flash']},{provider:'custom',label:'自定义 OpenAI 接口',baseUrl:'',model:'',hasApiKey:false,maskedApiKey:'',suggestedModels:[]}],messages:[]};
  try{state=JSON.parse(native.aiChatState())}catch(_){}
  return state;
 }

 function currentProviderConfig(){
  const s=state||loadState();
  return (s.providers||[]).find(p=>p.provider===selectedProvider)||s.providers?.[0]||{provider:selectedProvider,baseUrl:'',model:'',hasApiKey:false,maskedApiKey:'',suggestedModels:[]};
 }

 function open(initialMode='chat'){
  const d=dialog();if(!d)return;
  const s=loadState();
  selectedProvider=s.provider||'siliconflow';
  clearKeyPending=false;testStatus=null;
  mode=initialMode==='config'||!s.hasApiKey?'config':'chat';
  render();
  if(!d.open)d.showModal();
  if(mode==='chat')setTimeout(()=>document.getElementById('ai-chat-input')?.focus(),60);
 }

 function openConfig(){open('config')}
 function close(){const d=dialog();if(d?.open){const t=d.querySelector('.toast');if(t)document.body.append(t);d.close()}}
 function back(){const d=dialog();if(d?.open){close();return true}return false}

 function onEvent(payload){
  if(!payload)return;
  if(payload.kind==='state'&&payload.state){
   state=payload.state;
   if(dialog()?.open&&mode==='chat')render();
  }else if(payload.kind==='test'){
   testStatus={ok:!!payload.ok,message:payload.message||''};
   if(dialog()?.open&&mode==='config')renderTestStatus();
  }
 }

 function renderTestStatus(){
  const el=document.getElementById('ai-test-status');
  if(!el)return;
  if(!testStatus){el.hidden=true;return}
  el.hidden=false;
  el.className='ai-test-status '+(testStatus.ok?'ok':'err');
  el.textContent=testStatus.message;
 }

 function render(){
  const s=state||loadState();
  const activeMeta=(s.providers||[]).find(p=>p.provider===s.provider);
  if(subEl()){
   subEl().textContent=s.hasApiKey?`${activeMeta?.label||s.provider} · ${s.model}`:'未配置 API Key · 仅在你主动提问时联网';
  }
  const toggleBtn=document.getElementById('ai-mode-toggle');
  if(toggleBtn){
   toggleBtn.setAttribute('aria-label',mode==='config'?'返回对话':'模型与密钥设置');
   toggleBtn.innerHTML=mode==='config'?'<i class="fa-solid fa-comments" aria-hidden="true"></i>':'<i class="fa-solid fa-gear" aria-hidden="true"></i>';
  }
  if(composerEl())composerEl().hidden=mode!=='chat';
  if(mode==='config')renderConfig(s);
  else renderChat(s);
 }

 function renderConfig(s){
  const cfg=currentProviderConfig();
  const chips=(cfg.suggestedModels||[]).map(m=>`<button type="button" class="ai-model-chip ${cfg.model===m?'active':''}" data-ai-model="${esc(m)}">${esc(m)}</button>`).join('');
  bodyEl().innerHTML=`<div class="ai-config">
   <div class="ai-field"><label>选择服务商</label>
    <div class="direction-choices" id="ai-provider-tabs">
     <button type="button" data-ai-provider="siliconflow" class="${selectedProvider==='siliconflow'?'active':''}">硅基流动</button>
     <button type="button" data-ai-provider="aliyun" class="${selectedProvider==='aliyun'?'active':''}">阿里云百炼</button>
     <button type="button" data-ai-provider="custom" class="${selectedProvider==='custom'?'active':''}">自定义</button>
    </div>
   </div>
   <div class="ai-field"><label for="ai-base-url">接口 Base URL（OpenAI 兼容）</label><input id="ai-base-url" type="url" value="${esc(cfg.baseUrl||'')}" placeholder="https://api.siliconflow.cn/v1"/></div>
   <div class="ai-field"><label for="ai-model-name">模型名称</label><input id="ai-model-name" type="text" value="${esc(cfg.model||'')}" placeholder="如 deepseek-ai/DeepSeek-V4.1-Flash"/>${chips?`<div class="ai-model-chips">${chips}</div>`:''}</div>
   <div class="ai-field"><label for="ai-api-key">API Key（仅保存在本机，不会写入代码或上传）</label>
    <div class="ai-key-row"><input id="ai-api-key" type="password" autocomplete="off" placeholder="${cfg.hasApiKey&&!clearKeyPending?`已保存 ${esc(cfg.maskedApiKey)}（留空保持不变）`:'请输入 sk-... 密钥'}"/><button type="button" class="ai-key-clear" id="ai-clear-key" ${cfg.hasApiKey&&!clearKeyPending?'':'hidden'}>清除</button></div>
   </div>
   <div class="sheet-row"><span><b>允许参考本月脱敏收支汇总</b><small>问答时携带本月分类统计和近 15 笔金额/商户（不含截图与交易单号）</small></span><button type="button" class="switch" id="ai-include-summary" role="switch" aria-checked="${s.includeSummary!==false}"></button></div>
   <div id="ai-test-status" class="ai-test-status" hidden></div>
   <div class="sheet-actions"><button type="button" id="ai-test-btn">测试连接</button><button type="button" class="primary" id="ai-save-btn">保存并使用</button></div>
  </div>`;
  renderTestStatus();
  document.querySelectorAll('[data-ai-provider]').forEach(btn=>btn.onclick=()=>{
   selectedProvider=btn.dataset.aiProvider;clearKeyPending=false;testStatus=null;renderConfig(s);
  });
  document.querySelectorAll('[data-ai-model]').forEach(btn=>btn.onclick=()=>{
   document.getElementById('ai-model-name').value=btn.dataset.aiModel;
   document.querySelectorAll('[data-ai-model]').forEach(c=>c.classList.toggle('active',c===btn));
  });
  const clearBtn=document.getElementById('ai-clear-key');
  if(clearBtn)clearBtn.onclick=()=>{clearKeyPending=true;clearBtn.hidden=true;document.getElementById('ai-api-key').placeholder='密钥将在保存后清除'};
  const sumBtn=document.getElementById('ai-include-summary');
  sumBtn.onclick=()=>sumBtn.setAttribute('aria-checked',String(sumBtn.getAttribute('aria-checked')!=='true'));
  const collect=()=>({provider:selectedProvider,baseUrl:document.getElementById('ai-base-url').value.trim(),model:document.getElementById('ai-model-name').value.trim(),apiKey:document.getElementById('ai-api-key').value.trim(),clearApiKey:clearKeyPending,includeSummary:sumBtn.getAttribute('aria-checked')==='true'});
  document.getElementById('ai-test-btn').onclick=()=>{
   if(!native?.aiTestConnection){notify('请在 Android 应用内测试连接');return}
   testStatus={ok:true,message:'正在测试模型连接…'};renderTestStatus();
   native.aiTestConnection(JSON.stringify(collect()));
  };
  document.getElementById('ai-save-btn').onclick=()=>{
   if(!native?.aiSaveConfig){notify('请在 Android 应用内保存配置');return}
   const res=JSON.parse(native.aiSaveConfig(JSON.stringify(collect())));
   if(!res.ok){notify(res.error||'保存失败');return}
   state=res;clearKeyPending=false;mode='chat';render();notify('AI 配置已保存');
  };
 }

 function renderChat(s){
  const msgs=s.messages||[];
  const list=bodyEl();
  if(!msgs.length){
   list.innerHTML=`<div class="ai-welcome"><strong>你好，我是知账 AI 助手</strong>你可以直接用自然语言记账，或者让我分析本月花销、给预算建议。
    <div class="ai-prompts">
     <button type="button" class="ai-chip" data-ai-prompt="帮我分析一下本月的收支情况和主要花销">分析本月支出</button>
     <button type="button" class="ai-chip" data-ai-prompt="刚刚楼下买菜花了 32.5 元，微信支付">记一笔：买菜 32.5</button>
     <button type="button" class="ai-chip" data-ai-prompt="根据我这个月的花销，给几条实用的省钱建议">给个省钱建议</button>
    </div></div>`;
   list.querySelectorAll('[data-ai-prompt]').forEach(b=>b.onclick=()=>send(b.dataset.aiPrompt));
  }else{
   list.innerHTML=msgs.map(m=>{
    const draftHtml=m.draft?renderDraftCard(m.id,m.draft):'';
    return `<div class="ai-msg ${m.role==='user'?'user':'assistant'} ${m.isError?'is-error':''}"><div class="ai-bubble">${esc(m.text)}</div>${draftHtml}</div>`;
   }).join('')+(s.busy?'<div class="ai-msg assistant"><div class="ai-bubble">正在思考…</div></div>':'');
   bindDraftActions(msgs);
  }
  const sendBtn=document.getElementById('ai-chat-send');
  if(sendBtn)sendBtn.disabled=!!s.busy;
  list.scrollTop=list.scrollHeight;
 }

 function renderDraftCard(msgId,d){
  const income=d.direction==='INCOME';
  const accLine=d.accountName?` · 账户：${esc(d.accountName)}`:'';
  const noteLine=d.note?`<br/>备注：${esc(d.note)}`:'';
  const actions=d.saved?`<div class="ai-draft-actions"><span class="ai-draft-saved">✓ 已入账</span>${d.savedRecordId?`<button type="button" data-ai-open="${d.savedRecordId}">查看账单</button>`:''}</div>`:`<div class="ai-draft-actions"><button type="button" data-ai-edit="${msgId}">去编辑</button><button type="button" class="primary" data-ai-confirm="${msgId}">确认记账</button></div>`;
  return `<div class="ai-draft-card"><div class="ai-draft-head"><span class="ai-draft-dir ${income?'income':''}">${income?'收入草稿':'支出草稿'}</span><span class="ai-draft-amount">${income?'+':'−'} ¥${esc(d.amount)}</span></div><div class="ai-draft-meta">${esc(d.merchant)} · ${esc(d.category)}${accLine}${noteLine}</div>${actions}</div>`;
 }

 function bindDraftActions(msgs){
  bodyEl().querySelectorAll('[data-ai-confirm]').forEach(btn=>btn.onclick=()=>{
   const id=Number(btn.dataset.aiConfirm);
   const msg=msgs.find(m=>m.id===id);if(!msg?.draft||!native?.save)return;
   const d=msg.draft;
   const payload={id:0,amount:String(d.amount),direction:d.direction,merchant:d.merchant,category:d.category,occurredAt:Number(d.occurredAt)||Date.now(),note:d.note||'',confirmDuplicate:true};
   if(d.accountUid)payload.accountUid=d.accountUid;
   const res=JSON.parse(native.save(JSON.stringify(payload)));
   if(!res.ok){notify(res.error||'记账失败');return}
   msg.draft.saved=true;msg.draft.savedRecordId=res.id;render();
   native.aiMarkDraftSaved?.(id,res.id);
   refresh();notify('已记账 ¥ '+d.amount);
  });
  bodyEl().querySelectorAll('[data-ai-edit]').forEach(btn=>btn.onclick=()=>{
   const id=Number(btn.dataset.aiEdit);
   const msg=msgs.find(m=>m.id===id);if(!msg?.draft)return;
   const d=msg.draft;close();newDraft();
   draft={id:0,recognitionTaskId:0,amount:String(d.amount),direction:d.direction,merchant:d.merchant,category:d.category,occurredAt:Number(d.occurredAt)||Date.now(),note:d.note||'',state:'REVIEW',source:'MANUAL',...(d.accountUid?{accountUid:d.accountUid,accountSelectionSpecified:true}:{}),rawText:''};
   updateEditor();showRecordPanel(null);
  });
  bodyEl().querySelectorAll('[data-ai-open]').forEach(btn=>btn.onclick=()=>{
   const rid=Number(btn.dataset.aiOpen);close();openRecord(rid,false);
  });
 }

 function send(presetText=null){
  const input=document.getElementById('ai-chat-input');
  const text=(presetText!==null?presetText:input?.value||'').trim();
  if(!text)return;
  if(!native?.aiSendMessage){notify('请在 Android 应用中使用 AI 对话');return}
  if(!state?.hasApiKey){mode='config';render();notify('请先配置 API Key');return}
  const res=JSON.parse(native.aiSendMessage(text,month));
  if(!res.ok){notify(res.error||'发送失败');return}
  if(presetText===null&&input)input.value='';
  loadState();render();
 }

 function bind(){
  const toggle=document.getElementById('ai-mode-toggle');
  if(toggle)toggle.onclick=()=>{mode=mode==='config'?'chat':'config';render()};
  const clearBtn=document.getElementById('ai-chat-clear');
  if(clearBtn)clearBtn.onclick=()=>{if(!native?.aiClearHistory)return;state=JSON.parse(native.aiClearHistory());render()};
  const closeBtn=document.getElementById('ai-chat-close');
  if(closeBtn)closeBtn.onclick=close;
  const sendBtn=document.getElementById('ai-chat-send');
  if(sendBtn)sendBtn.onclick=()=>send();
  const input=document.getElementById('ai-chat-input');
  if(input)input.onkeydown=e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}};
 }
 bind();
 return {open,openConfig,close,back,onEvent};
})();
