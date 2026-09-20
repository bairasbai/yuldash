export async function until(test) {
  for(let i=0;i<200;i++){if(await test())return;await new Promise(setImmediate);}
  throw new Error('Expected asynchronous queue state was not reached');
}
export async function storedActions(){
 const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('yuldash-outbox-v1',1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
 try{return await new Promise((resolve,reject)=>{const tx=db.transaction('actions'),r=tx.objectStore('actions').getAll();tx.oncomplete=()=>resolve(r.result);tx.onabort=()=>reject(tx.error);});}finally{db.close();}
}
