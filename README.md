# Coverage41C

Замеры покрытия для платформы 1С: Предприятие 8

https://42clouds.com/ru-ru/techdocs/raschyot-pokrytiya-koda-1C-testami.html

### Подготовка окружения

1) Устанавливаем EDT либо копируем необходимые плагины с компьютера с установленной EDT. Для работы программы нужны плагины `com._1c.g5.v8.dt.debug.core_*.jar`, `com._1c.g5.v8.dt.debug.model_*.jar`, по умолчанию они находятся по пути `%USER_HOME%\plugins`.
2) Устанавливаем переменную окружения `EDT_LOCATION` в папку, где размещены требуемые плагины EDT.
3) Выгружаем исходники конфигурации, расширения или внешней обработки в файлы (если у вас проект EDT, то этот шаг пропускаем - он и так хранится в файлах)
4) Скачиваем последнюю версию **Coverage41C** со страницы https://github.com/1c-syntax/Coverage41C/releases

### Сценарий использования:

1) Включаем http-отладку на сервере либо запускаем дебаг сервер для файловой базы

   * клиент-серверный сценарий: 
    
     путём добавления к флагу `-debug` флага `-http` в строке запуска службы агента сервера

   * файловый сценарий: 
    
     путём ручного запуска дебаг сервера dbgs.exe, к которому будет подключаться **Coverage41C** и клиент файловой базы, например:
     ```shell
     dbgs.exe --addr=127.0.0.1 --port=1550
     ```
    
     *Примечание: если включить протокол отладки http через конфигуратор "Сервис -> Параметры -> Отладка", то Coverage41C не сможет подключиться к серверу отладки, т.к. одновременно к информационной базе может быть подключен только один интерфейс отладки.*

2) Проверяем что сервер отладки dbgs.exe (https://its.1c.ru/db/edtdoc/content/197/hdoc/_top/dbgs) запустился и работает. Для этого в браузере открываем его, адрес по умолчанию http://127.0.0.1:1550/. В случае успеха выдолжны увидеть сообщение "... it works!".

3) Запускаем клиентское приложение 1С, в которм будем выполнять тесты.
    
    Клиент должен быть подключен к нашему серверу отладки. В файловом режиме запускаем с флагами `/debug -http -attach /debuggerURL «адрес отладчика»` или через меню "Настройка -> Параметры -> Сервер отладки" в самом клиенте, см. https://its.1c.ru/db/v837doc#bookmark:adm:TI000000495.

4) Запускаем анализ покрытия командой:
  
    ```shell
    Coverage41C start -i <ИмяИнформационнойБазыВКластере> -P <ПутьКПроекту> -s <ПутьКИсходникам> -o <ИмяВыходногоФайлаПокрытия>
    ```
    
    Для файловой базы нужно указать адрес отладчика и предопределённое имя информационной базы:

    ```shell
    Coverage41C start -i DefAlias -u http://127.0.0.1:<Порт> -P <ПутьКПроекту> -s <ПутьКИсходникам> -o <ИмяВыходногоФайлаПокрытия>`
    ```
       
    Если `ПутьКПроекту` и `ПутьКИсходникам` совпадают, можно указать только `-P <ПутьКПроекту>`. Если пути не указывать, будет выходной файл ьбудет собран в формате raw (в формате внутренних uuid, без указания имен файлов модулей) и потребуется конвертация (см.п. 9).

5) (Опционально, полезно для конвейера) Проверяем статус программы для клиент-серверной:

    ```shell
    Coverage41C check -i <ИмяИнформационнойБазыВКластере>
    ```
   
    для файловой:

    ```shell
    Coverage41C check -i DefAlias -u http://127.0.0.1:<Порт>
   ```
   
6) Выполняем тесты

7) Завершаем работу клиента

8) Останавливаем Coverage41C нажатием Ctrl+C в окне терминала или командой для клиент-сервернок
    
    ```shell
    Coverage41C stop -i <ИмяИнформационнойБазыВКластере>
    ```
   
    для файловой
    
   ```shell
    Coverage41C stop -i DefAlias -u http://127.0.0.1:<Порт>
    ```
   
    Также возможна запись файла покрытия без остановки замеров командой `dump`.

9) Если команде `start` передавался путь к исходникам, то `convert` не нужен. В противном случае программа сформирует raw формат, который нужно преобразовать в Generic Coverage командой `convert`:
    
   ```shell
   Coverage41C convert -c <ИмяВходногоФайлаПокрытияRaw> -o <ИмяВыходногоФайлаПокрытия> -P <ПутьКПроекту> -s <ПутьКИсходникам>
   ```

10) Полученный файл в формате genericCoverage.xml загружаем в SonarQube (файл формата LCov можно просмотреть в VSCode, ReportTool, genhtml и многих других программах).

### Примеры

#### Пример команд запуска на файловой базе в формате Generic Coverage

```shell
dbgs.exe --addr=127.0.0.1 --port=1550
Coverage41C start -i DefAlias -u http://127.0.0.1:1550 -P C:\path\to\sources\ -o genericCoverage.xml
Coverage41C check -i DefAlias -u http://127.0.0.1:1550
Coverage41C stop -i DefAlias -u http://127.0.0.1:1550
```

#### Пример команд запуска на файловой базе в формате Raw с последующей конвертацей

```shell
dbgs.exe --addr=127.0.0.1 --port=1550
Coverage41C start -i DefAlias -u http://127.0.0.1:1550 -o rawCoverage.xml
Coverage41C check -i DefAlias -u http://127.0.0.1:1550
Coverage41C stop -i DefAlias -u http://127.0.0.1:1550
Coverage41C convert -c rawCoverage.xml -o genericCoverage.xml -P C:\path\to\sources\
```

#### Пример выходного файла покрытия
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

### Справка из командной строки

```shell
Coverage41C --help
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

Так же доступна справка по каждой из команд:

```shell
Coverage41C start -h
Usage: Coverage41C start [-hV] [-p] [--verbose] [-e=<extensionName>]
                         [-i=<infobaseAlias>] [-o=<outputFile>] [--opid=<opid>]
                         [-P=<projectDirName>] [-p:env=<passwordEnv>]
                         [-r=<removeSupport>] [-s=<srcDirName>]
                         [-t=<pingTimeout>] [-u=<debugServerUrl>] [-u:
                         file=<debugServerUrlFileName>]
                         [-x=<externalDataProcessorUrl>] [-a
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
