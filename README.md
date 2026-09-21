# Coverage41C
Замеры покрытия для платформы 1С:Предприятие 8

https://42clouds.com/ru-ru/techdocs/raschyot-pokrytiya-koda-1C-testami.html

Сценарий использования:

0) Устанавливаем EDT (для работы программы нужны как минимум его библиотеки ```com._1c.g5.v8.dt.debug.core_*.jar```, ```com._1c.g5.v8.dt.debug.model_*.jar```). Если держать EDT на данной машине затруднительно, их можно скопировать в любую папку, установить параметр окружения EDT_LOCATION в данную папку и удалить EDT.
1) Скачиваем последнюю версию со страницы https://github.com/proDOOMman/Coverage41C/releases. Для работы нужна Java 21 или новее
2) Включаем http-отладку 
* клиент-серверный сценарий: 
    
     путём добавления к флагу -debug флага -http в строке запуска службы агента сервера

* файловый сценарий: 
    
     путём ручного запуска дебаг сервера dbgs.exe (например так: ```dbgs.exe --addr=127.0.0.1 --port=1550```), к которому будет подключаться Coverage41C и клиент файловой базы (запущенный с флагами ```/debug -http -attach /debuggerURL «адрес отладчика»``` или через меню "Настройка->Параметры->Сервер отладки" в самом клиенте), см. https://its.1c.ru/db/v837doc#bookmark:adm:TI000000495 
    
     *Примечание: если включить протокол отладки http через конфигуратор Сервис -> Параметры -> Отладка, то Coverage41C не сможет подключиться к серверу отладки, т.к. одновременно к информационной базе может быть подключен только один интерфейс отладки.*

3) Проверяем что сервер отладки dbgs.exe (https://its.1c.ru/db/edtdoc/content/197/hdoc/_top/dbgs) запустился и работает. Для этого в браузере открываем его, адрес по умолчанию http://127.0.0.1:1550/. В случае успеха вы должны увидеть сообщение "... it works!".
4) Выгружаем исходники конфигурации, расширения или внешние обработки в файлы (если у вас проекта EDT, то этот шаг пропускаем - он и так хранится в файлах)
5) Запускаем анализ покрытия командой ```Coverage41C start -i <ИмяИнформационнойБазыВКластере> -P <ПутьКПроекту> -s <ПутьКИсходникам> -o <ИмяВыходногоФайлаПокрытия> -e <ИмяРасширения>```. Для файловой базы нужно указать адрес отладчика и предопределённое имя информационной базы ```Coverage41C start -i DefAlias -u http://127.0.0.1:<Порт> -P <ПутьКПроектуEDT>``` или ```Coverage41C start -i DefAlias -u http://127.0.0.1:<Порт> -s <ПутьКИсходникам>```. Чтобы за один прогон снять покрытие конфигурации и расширений, добавьте для каждого расширения опцию ```--extension <ИмяРасширения>=<ПутьКИсходникамРасширения>``` (см. [ниже](#конфигурация-и-расширения-за-один-прогон))
6) (Опционально, полезно для конвейера) Проверяем статус программы командой ```Coverage41C check -i <ИмяИнформационнойБазыВКластере>``` или ```Coverage41C check -i DefAlias -u http://127.0.0.1:1550``` для файловой.
7) Выполняем тесты
8) Останавливаем программу нажатием Ctrl+C в окне терминала или командой ```Coverage41C stop -i <ИмяИнформационнойБазыВКластере> -u http://127.0.0.1:<Порт>```. Также возможна запись файла покрытия без остановки замеров командой ```dump```.
9) Полученный файл в формате genericCoverage.xml загружаем в SonarQube (файл формата LCov можно просмотреть в VSCode, ReportTool, genhtml и многих других программах).

Если команде start передавался путь к исходникам, то convert не нужен.
 
Примеры запуска на файловой базе для проекта EDT:
```cmd
Coverage41C start -i DefAlias -u http://127.0.0.1:1550 -P C:\path\to\sources\ -o genericCoverage.xml
```
### Конфигурация и расширения за один прогон

Исходники конфигурации задаются как обычно (`-P`, `-s`), исходники каждого расширения - повторяемой опцией `--extension <ИмяРасширения>=<Путь>`. Имя - имя расширения в информационной базе, путь - относительно каталога проекта `-P` (с `--extension` он обязателен):
```cmd
Coverage41C start -i DefAlias -u http://127.0.0.1:1550 -P . -s src/cf --extension Доработки=src/cfe/Доработки --extension Интеграция=src/cfe/Интеграция -o genericCoverage.xml
```
В одном файле покрытия окажутся модули конфигурации и всех перечисленных расширений, пути - относительно `-P` (например `src/cfe/Доработки/CommonModules/ОбщийМодуль/Ext/Module.bsl`). Модули расширений, для которых не передан путь, в замер не попадают.

То же умеет `convert`: замер без исходников (RAW режим) сохраняет для модулей расширений имя расширения в атрибуте `extension` (нет атрибута - модуль конфигурации), поэтому один сырой файл раскладывается по всем деревьям сразу:
```cmd
Coverage41C start -i DefAlias -u http://127.0.0.1:1550 -o internal.xml
Coverage41C convert -c internal.xml -P . -s src/cf --extension Доработки=src/cfe/Доработки --extension Интеграция=src/cfe/Интеграция -o genericCoverage.xml
```
Прежние сценарии работают как раньше: `-e <ИмяРасширения> -s <ПутьКИсходникамРасширения>` для одного расширения, `convert -s <ПутьКИсходникам>` без `--extension` (атрибут `extension` при этом не учитывается), сырые файлы предыдущих версий.

При завершении работы создаётся файл покрытия вида:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<coverage version="1">
    <file path="CommonCommands/СменитьПароль/Ext/CommandModule.bsl">
        <lineToCover covered="true" lineNumber="13"/>
        <lineToCover covered="false" lineNumber="9"/>
    </file>
    <file path="Catalogs/ОбработчикиСобытийRMQ/Forms/ФормаСписка/Ext/Form/Module.bsl">
        <lineToCover covered="false" lineNumber="11"/>
        <lineToCover covered="true" lineNumber="5"/>
        <lineToCover covered="true" lineNumber="4"/>
```

Справка из командной строки:
```cmd
>Coverage41C --help
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
Также доступна справка по каждой из команд:
```cmd
>Coverage41C start -h
Usage: Coverage41C start [-hV] [-p] [--verbose] [-e=<extensionName>]
                         [-i=<infobaseAlias>] [-o=<outputFile>] [--opid=<opid>]
                         [-P=<projectDirName>] [-p:env=<passwordEnv>]
                         [-r=<removeSupport>] [-s=<srcDirName>]
                         [-t=<pingTimeout>] [-u=<debugServerUrl>] [-u:
                         file=<debugServerUrlFileName>]
                         [-x=<externalDataProcessorUrl>]
                         [--extension=<extensionName>=<path>]... [-a
                         [=<autoconnectTargets>...]]... [-n
                         [=<debugAreaNames>...]]...
Start measure and save coverage data to file
  -i, --infobase=<infobaseAlias>
                           InfoBase name. File infobase uses 'DefAlias' name.
                             Default - DefAlias
  -u, --debugger=<debugServerUrl>
                           Debugger url. Default - http://127.0.0.1:1550/
      -u:file, --debugger:file=<debugServerUrlFileName>
                           Debugger url file name
  -e, --extensionName=<extensionName>
                           Extension name
  -x, --externalDataProcessor=<externalDataProcessorUrl>
                           External data processor (or external report) url
  -s, --srcDir=<srcDirName>
                           Directory with sources exported to xml
  -P, --projectDir=<projectDirName>
                           Directory with project
  -r, --removeSupport=<removeSupport>
                           Remove support values: NOT_EDITABLE,
                             EDITABLE_SUPPORT_ENABLED, NOT_SUPPORTED, NONE.
                             Default - NONE
      --extension=<extensionName>=<path>
                           Extension name and directory with its sources
                             exported to xml (relative to project directory).
                             Can be repeated. Requires --projectDir
  -o, --out=<outputFile>   Output file name
  -p, --password           Dbgs password
      -p:env, --password:env=<passwordEnv>
                           Password environment variable name
  -n, --areanames[=<debugAreaNames>...]
                           Debug area names (not for general use!)
  -a, --autoconnectTargets[=<autoconnectTargets>...]
                           Autoconnect debug targets (not for general use!):
                             Unknown, Client, ManagedClient, WEBClient,
                             COMConnector, Server, ServerEmulation, WEBService,
                             HTTPService, OData, JOB, JobFileMode,
                             MobileClient, MobileServer, MobileJobFileMode,
                             MobileManagedClient
  -t, --timeout=<pingTimeout>
                           Ping timeout. Default - 1000
      --verbose            If you need more logs. Default - false
      --opid=<opid>        Owner process PID
  -h, --help               Show this help message and exit.
  -V, --version            Print version information and exit.
```
