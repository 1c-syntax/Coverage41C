# Coverage41C
Замеры покрытия для платформы 1С: Предприятие 8

https://42clouds.com/ru-ru/techdocs/raschyot-pokrytiya-koda-1C-testami.html

Сценарий использования:

0) Устанавливаем EDT (для работы программы нужны как минимум его библиотеки ```com._1c.g5.v8.dt.debug.core_*.jar```, ```com._1c.g5.v8.dt.debug.model_*.jar```). Если держать EDT на данной машине затруднительно, из можно скопировать в любую папку, установить параметр окружения EDT_LOCATION в данную папку и удалить EDT.
1) Скачиваем последнюю версию со страницы https://github.com/proDOOMman/Coverage41C/releases
2) Включаем http-отладку на сервере 1С-Предприятия потём добавления к флагу -debug флага -http (или добавляем флаги ```/debug -http -attach /debuggerURL «адрес отладчика»``` в строку запуска файловой базы, см. https://its.1c.ru/db/v837doc#bookmark:adm:TI000000495)
3) Проверяем что dbgs.exe (https://its.1c.ru/db/edtdoc/content/197/hdoc/_top/dbgs) запустился и работает. Для этого в браузере открываем его, адрес по умолчанию http://127.0.0.1:1550/. В случае успеха выдолжны увидеть сообщение "... it works!".
4) Выгружаем исходники конфигурации или расширения в файлы.
5) Запускаем анализ покрытия командой ```Coverage41C start -i <ИмяИнформационнойБазыВКластере> -P <ПутьКПроекту> -s <ПутьКИсходникамОтносительноКорняПроекта> -o <ИмяВыходногоФайлаПокрытия> -e <ИмяРасширения>```. Для файловой базы нужно указать адрес отладчика и предопределённое имя информационной базы ```-i DefAlias -u http://127.0.0.1:<Порт>```.
6) (Опционально, полезно для конвейера) Проверяем статус программы командой ```Coverage41C check -i <ИмяИнформационнойБазыВКластере>```.
7) Выполняем тесты
8) Останавливаем программу нажатием Ctrl+C в окне терминала или командой ```Coverage41C stop -i <ИмяИнформационнойБазыВКластере> -u http://127.0.0.1:<Порт>```. Также возможна запись файла покрытия без остановки замеров командой ```dump```.
9) Полученный файл в формате genericCoverage.xml загружаем в SonarQube.

```cmd
Usage: Coverage41C [-hV] [COMMAND]
Make measures from 1C:Enterprise and save them to genericCoverage.xml file
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  start    Start measure and save coverage data to file
  stop     Stop main application and save coverage to file
  check    Check is main application ready
  clean    Clear coverage data in main application
  dump     Save coverage data to file
  convert  Convert results from internal uuid-based format
```
