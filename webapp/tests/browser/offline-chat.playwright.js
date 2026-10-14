async(page)=>{
  if(await page.evaluate(()=>navigator.onLine))throw Error('Fixture must be offline');
  await page.getByRole('textbox',{name:'Сообщение',exact:true}).waitFor();
  const banner=await page.locator('.offline-banner').boundingBox();
  const header=await page.locator('.tchat__head').boundingBox();
  const input=await page.getByRole('textbox',{name:'Сообщение',exact:true}).boundingBox();
  if(header.y < banner.y+banner.height)throw Error('Offline banner covers chat header');
  if(input.y+input.height > page.viewportSize().height+0.01)throw Error('Chat composer is below viewport');
  await page.getByRole('button',{name:'Назад',exact:true}).click({timeout:3000});
  if(/\/chat$/.test(new URL(page.url()).pathname))throw Error('Chat Back did not navigate');
  return {result:'PASS',banner,header,input};
}
