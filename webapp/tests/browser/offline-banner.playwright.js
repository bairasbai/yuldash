async (page) => {
  if (await page.evaluate(() => navigator.onLine)) throw new Error('Fixture must be offline');
  await page.locator('.offline-banner').waitFor({state:'visible'});
  const tab = page.getByRole('tab', {name:'Курьер', exact:true});
  await tab.waitFor({state:'visible'});
  await tab.click({timeout:3000});
  const banner = await page.locator('.offline-banner').boundingBox();
  const button = await tab.boundingBox();
  if (banner.y + banner.height > button.y) throw new Error('Offline banner overlaps mode controls');
  if (await tab.getAttribute('aria-selected') !== 'true') throw new Error('Courier selection failed');
  const text = await page.locator('main').innerText();
  if (!/Скоро в Юлдаше|Тиҙҙән Юлдашта/.test(text)) throw new Error('Disabled direction lacks Soon');
  return {result:'PASS',banner,button,text};
}
