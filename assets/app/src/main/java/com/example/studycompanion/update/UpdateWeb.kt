package com.example.studycompanion.update

fun updateWebHtml() = """<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Study Gadget 更新</title>
<style>body{font:17px system-ui;max-width:640px;margin:36px auto;padding:20px;background:#101828;color:#eef4ff}button,input{font:inherit;padding:12px;margin:10px 0;max-width:100%}button{background:#81d5b5;border:0;border-radius:10px}pre{white-space:pre-wrap}a{color:#9bdcff}</style>
<h1>アプリの更新・アップロード</h1><p>APK・Study Gadget用3DSX・sgupdateを選んでください。</p><input type="file" id="file" accept=".apk,.3dsx,.sgupdate,.zip"><br><button id="upload">取り込む</button><pre id="status" role="status"></pre><p>3DS版はUPDATE→INSTALLで受信します。スマホ版はアプリの更新画面からインストールしてください。</p>
<script>
const token=new URLSearchParams(location.search).get('t')||'';
const status=document.getElementById('status'),button=document.getElementById('upload');
button.onclick=()=>{const f=document.getElementById('file').files[0];if(!f){status.textContent='ファイルを選んでください';return}if(f.size>100663296){status.textContent='96 MiB以下にしてください';return}
button.disabled=true;const xhr=new XMLHttpRequest();xhr.open('POST','/api/update/upload');xhr.setRequestHeader('Authorization','Bearer '+token);xhr.setRequestHeader('Content-Type','application/octet-stream');xhr.timeout=120000;
xhr.upload.onprogress=e=>{status.textContent=e.lengthComputable?'送信中 '+Math.round(e.loaded/e.total*100)+'%':'送信中…'};
xhr.upload.onload=()=>{status.textContent='端末で検証・保存しています…'};
xhr.onload=()=>{button.disabled=false;status.textContent=xhr.status==200?'取り込み完了。3DSでUPDATE→INSTALL、スマホはアプリの更新画面へ。':'取り込み失敗: '+xhr.responseText};
xhr.onerror=xhr.ontimeout=()=>{button.disabled=false;status.textContent='通信に失敗しました。親機サーバーと接続を確認してください。'};xhr.send(f)};
</script></html>""".trimIndent()
