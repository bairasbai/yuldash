// ================================================================
//  Ленивая загрузка экрана, которая переживает обновление сайта.
//
//  Проблема, ради которой это заведено: экраны лежат отдельными файлами
//  с хешем в имени. Выходит новая версия — старые файлы с сервера пропадают.
//  А вкладка, открытая со вчера, при переходе просит именно старый файл и
//  получает 404. Человек видит белый экран и решает, что сайт сломался.
//
//  Что делаем: одна тихая перезагрузка. Свежий index.html подтянет новые
//  имена файлов, и человек окажется там, куда шёл.
//
//  Перезагружаем РОВНО ОДИН раз (метка в sessionStorage): если файла нет
//  по другой причине — нет сети, сервер лежит — циклиться нельзя, иначе
//  вместо белого экрана человек получит бесконечное мигание.
// ================================================================
import { lazy, type ComponentType } from "react";

const RELOAD_FLAG = "yuldash.chunkReload";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function lazyScreen<T extends ComponentType<any>>(
  load: () => Promise<{ default: T }>
) {
  return lazy(() =>
    load().catch((err) => {
      let already = false;
      try {
        already = sessionStorage.getItem(RELOAD_FLAG) === "1";
        if (!already) sessionStorage.setItem(RELOAD_FLAG, "1");
      } catch {
        // приватный режим — метку не сохранить; тогда лучше не перезагружать
        // вовсе, чем зациклиться
        already = true;
      }
      if (!already) {
        window.location.reload();
        // Возвращаем «вечный» промис: страница уже перезагружается,
        // рисовать ошибку по дороге незачем.
        return new Promise<{ default: T }>(() => {});
      }
      throw err;
    })
  );
}

/** Загрузка прошла — метку снимаем, чтобы следующее обновление тоже сработало. */
export function clearChunkReloadFlag(): void {
  try {
    sessionStorage.removeItem(RELOAD_FLAG);
  } catch {
    /* приватный режим — нечего снимать */
  }
}
