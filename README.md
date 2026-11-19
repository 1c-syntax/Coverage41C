# Coverage41C
Замеры покрытия для платформы 1С:Предприятие 8

https://42clouds.com/ru-ru/techdocs/raschyot-pokrytiya-koda-1C-testami.html

Сценарий использования:

0) Устанавливаем EDT. Для работы нужны JAR `com._1c.g5.v8.dt.debug.core_*.jar` и `com._1c.g5.v8.dt.debug.model_*.jar`. Укажите каталог с ними в `EDT_LOCATION` или оставьте поиск скрипту запуска. На Unix скрипт выбирает первую установку из `ring edt locations list`, в которой есть обе библиотеки.
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
Если основное дерево `-s` - тоже расширение, укажите его имя опцией `-e` (у `start` и `convert` одинаково), остальные расширения - через `--extension`:
```cmd
Coverage41C convert -c internal.xml -P . -e Доработки -s src/cfe/Доработки --extension Интеграция=src/cfe/Интеграция -o genericCoverage.xml
```
Прежние сценарии работают как раньше: `-e <ИмяРасширения> -s <ПутьКИсходникамРасширения>` для одного расширения, `convert -s <ПутьКИсходникам>` без `-e` и `--extension` для старых сырых файлов. В новых замерах источник отмечен явно, поэтому для конвертации отдельного расширения укажите его имя через `-e`.

Для внешней обработки или внешнего отчёта `-s` может указывать на файл метаданных. Для `-o` и `--exclusion-report` укажите отдельные файлы, чтобы сохранить дескриптор. Дополнительные расширения задаются каталогами через `--extension`. Для старого RAW без URL обработки `convert` использует `-x`, если ключ модуля совпадает с исходниками выбранной обработки. При наличии атрибутов `extension` он также проверяет выбранное расширение.

При завершении работы создаётся Generic Coverage XML. Для LCOV и Cobertura используйте `-f LCOV` или `-f COBERTURA`.
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<coverage version="1">
    <file path="CommonCommands/ОбщаяКоманда1/Ext/CommandModule.bsl">
        <lineToCover covered="true" lineNumber="13"/>
        <lineToCover covered="false" lineNumber="9"/>
    </file>
    <file path="Catalogs/Справочник1/Forms/ФормаСписка/Ext/Form/Module.bsl">
        <lineToCover covered="false" lineNumber="11"/>
        <lineToCover covered="true" lineNumber="5"/>
    </file>
</coverage>
```

## Настройка покрытия

Укажите `--config` в `start`, `convert` или `validate`. Параметр принимает JSON-файл или каталог. В каталоге читаются видимые файлы `*.json` верхнего уровня. Это позволяет хранить правила нескольких команд и применять их за один прогон. Области включения из разных файлов объединяются, правила исключений действуют на весь расчёт.

| Ключ | Назначение |
| --- | --- |
| `team` | Название команды для отчёта об исключениях. |
| `includeSubsystems` | Объекты выбранных подсистем и их потомков. Можно передать техническое имя, полный путь через точку или маску с `*`. Приоритет у точного пути, в том числе у имени корневой подсистемы. Короткое имя вложенной подсистемы должно быть уникальным, если оно не совпадает с корневым путём. |
| `includePrefix` | Объекты, формы, команды и подсистемы, имена которых начинаются с указанных префиксов. Результат объединяется с `includeSubsystems`. |
| `prefixScope` | Настраивает действие `includePrefix`. Ключи `subsystems`, `includeSubsystemContents`, `modules`, `routines` по умолчанию принимают значения `true`, `true`, `true`, `false`. При `routines: true` в охват входят также процедуры и функции с заданным префиксом внутри остальных модулей. |
| `exclude` | Исключает строки по правилам в `subsystems`, `objects`, `paths`, `lines`. |
| `auto` | Те же четыре вида правил для необязательных строк. Строка учитывается, когда она была исполнена. |
| `changeMarkers` | Объект или массив пар `begin` / `end` для включения блоков доработок. |
| `unclosedBlocks.enabled` | Разрешает автоматически определять конец блока доработки. По умолчанию выключено. |
| `includeUsers` | Список пользователей ИБ, чьи замеры собирает `start`. |
| `exclusionReport` | Путь к JSON-отчёту относительно корня проекта `-P` или абсолютный путь. |

В `exclude` и `auto` ключ `subsystems` принимает имена, полные пути и маски. `objects` принимает маски имён модулей, например `Catalog.Справочник1.ManagerModule`. `paths` принимает glob-шаблоны относительно `-P`, разделитель каталогов — `/`. `lines` принимает регулярные выражения для поиска в тексте строки. Имена, маски объектов, пути и префиксы учитывают регистр, регулярные выражения строк по умолчанию регистронезависимы. При пересечении правил `exclude` имеет приоритет над `auto`.

Примеры используют вымышленные названия и демонстрационные исходники из репозитория.

- [Полная конфигурация](examples/coverage41c.full.json) показывает все ключи, виды правил, стандартные и пользовательские метки.
- [Минимальная конфигурация](examples/coverage41c.minimal.json) задаёт только охват по подсистеме.
- [Фильтр пользователей](examples/coverage41c.users.json) подходит для сбора сырого файла без исходников.

Правила охвата и исключений рассчитаны на конфигурации и расширения. Для внешних обработок можно использовать маркеры `Cover:*` в модулях.

### Маркеры в исходниках

- `Cover:off` (`Cover:выкл`) исключает строки.
- `Cover:auto` (`Cover:авто`) делает строки необязательными до их исполнения.
- `Cover:on` (`Cover:вкл`) закрывает последний открытый диапазон.
- `Cover:file-off` (`Cover:файл-выкл`) перед первой строкой кода исключает весь модуль.

Отдельный комментарий `Cover:off` или `Cover:auto` открывает диапазон. Маркер после обычного оператора действует на его строку. На строке, открывающей метод, условие, цикл, `Попытка` или область, он применяется к блоку. Текст после маркера можно использовать как причину исключения. Незакрытый диапазон действует до конца модуля и вызывает предупреждение.

### Учёт доработок в типовых модулях

`changeMarkers` включает код между метками даже в модуле, исключённом целиком или находящемся вне охвата. Построчные исключения и `Cover:*` продолжают действовать. Пустой объект `{}` выбирает стандартные пары `//++` / `//--` и `//{{` / `//}}`. Пользовательские регулярные выражения `begin` и `end` сопоставляются с текстом комментария. Строка с кодом и меткой входит в блок, отдельный комментарий служит границей.

При `unclosedBlocks.enabled: true` незакрытый блок внутри метода заканчивается на последней строке метода. Вне метода используется конец ближайшей области или конец модуля. Граница и число добавленных строк видны в отчёте.

### Фильтрация замеров по пользователям ИБ

`start` использует список `includeUsers` или повторяемый `--include-user`. Параметр CLI заменяет список из конфигов для текущего прогона. Используется имя пользователя ИБ, которое сообщает сервер отладки, с учётом регистра.

Пакеты замеров от других или неопознанных пользователей отбрасываются до слияния. Если в замере есть только UUID, сборщик может взять имя из команды отладчика с тем же UUID. Регистр букв UUID на сопоставление не влияет. Некорректные UUID предметов отладки отклоняются. Если за весь прогон не найден ни один разрешённый предмет отладки, `stop` сообщает об ошибке, а `start` завершается с ненулевым кодом после записи отчёта. В сырой файл попадают уже отобранные замеры. Остальные правила из конфига можно применить при последующем `convert` с исходниками.

Пакет от неопознанного пользователя во время остановки также означает неполный сбор. Обе команды возвращают ошибку, сохраняя принятые замеры.

В первом терминале запустите сбор.

```sh
Coverage41C start -i DefAlias --config examples/coverage41c.users.json -o raw.xml
```

Во втором терминале дождитесь готовности, выполните тесты под пользователями `test_runner` или `test_ui` и остановите сбор.

```sh
Coverage41C check -i DefAlias
# Выполните тесты под test_runner или test_ui
Coverage41C stop -i DefAlias
```

После отключения профилирования сборщик ждёт последовательные ответы без новых пакетов замеров. Если сборщик не дождался корректных финальных ответов отладчика или не смог отключить профилирование, он сохраняет доступное покрытие. `start` и `stop` в этом случае возвращают ненулевой код. С начала остановки `clean` и `dump` отклоняются. Команда `stats` выводит сводку в stderr процесса `start`.

После завершения `start` и появления `raw.xml` запустите конвертацию.

```sh
Coverage41C convert -c raw.xml -P . -s src --config coverage41c.json -o genericCoverage.xml
```

На вход `convert` подавайте сырой XML с замерами. Укажите исходники через `-P`, `-s` или `--source`. Команда отклоняет готовый отчёт с путями к BSL-файлам, RAW с объявлением `DOCTYPE`, посторонним текстом внутри элементов или повреждёнными ключами источника. Старые RAW с ключами модулей читаются.

### Проверка правил и отчёт об исключениях

`validate` применяет правила к исходникам и выполняет те же проверки охвата и маркеров без подключения к серверу отладки. `--strict` завершает команду с ненулевым кодом при предупреждениях. Проверка показывает, какие строки войдут в расчёт. Итоговый процент формируется после тестового прогона.

На демонстрационной конфигурации репозитория минимальный пример проходит строгую проверку.

```sh
Coverage41C validate -P . -s src/test/resources/configuration \
  --config examples/coverage41c.minimal.json --strict
```

Полный пример запускайте на этой же конфигурации без `--strict`. Он показывает диагностику правил и меток, для которых в демонстрационных исходниках нет совпадений.

`--exclusion-report` сохраняет JSON со сводкой, правилами, причинами, затронутыми модулями, блоками доработок и предупреждениями. Путь в CLI считается от каталога запуска и выбирает, куда записать отчёт. Путь `exclusionReport` в конфиге считается от `-P`. При чтении каталога конфигов заданный в нём путь проверяется при загрузке, в том числе при переданном `--exclusion-report`. Для JSON-отчёта укажите путь, отличный от входных файлов и результата `-o` в `start` и `convert`. Команды учитывают ссылки, регистр, нормализацию Unicode имён и разные точки монтирования одного каталога.

### Правила для нескольких источников

При указании `--extension` правила применяются к основной конфигурации и перечисленным расширениям. Отчёт формируется по всем источникам, пути считаются от общего `-P`. Точное имя подсистемы должно встречаться хотя бы в одном источнике.

```sh
Coverage41C validate -P . -s src/cf \
  --extension 'ДемоРасширение=src/cfe/demo' --config coverage41c.json --strict
```

Альтернативный синтаксис `--source ИМЯ=КАТАЛОГ` перечисляет все исходники. Пустое имя обозначает основную конфигурацию. Каталоги должны быть внутри `-P`, а имена и физические каталоги должны быть уникальными. Две точки монтирования одного каталога считаются одним источником и в `--source`, и в `--extension`. В одном вызове используйте один способ задания источников. `--source` несовместим с `-s`, `--extension`, `-e` и `-x`.

## Справка из командной строки

```text
> Coverage41C --help
Usage: Coverage41C [-hV] [COMMAND]
Make measures from 1C:Enterprise and save them to genericCoverage.xml file
  -h, --help      Show this help message and exit.
  -V, --version   Print version information and exit.
Commands:
  start     Start measure and save coverage data to file
  stop      Stop main application and save coverage to file
  check     Check is main application ready
  clean     Clear coverage data in main application
  dump      Save coverage data to file
  stats     Print coverage statistic report
  convert   Convert results from internal uuid-based format
  validate  Validate coverage configuration and markers without measuring
```

<details>
<summary>Параметры start</summary>

```text
> Coverage41C start --help
Usage: Coverage41C start [-hV] [-p] [--verbose] [--config=<configFile>]
                         [-e=<extensionName>]
                         [--exclusion-report=<exclusionReportFile>]
                         [-f=<outputFormat>] [-i=<infobaseAlias>]
                         [-o=<outputFile>] [--opid=<opid>]
                         [-P=<projectDirName>] [-p:env=<passwordEnv>]
                         [-r=<removeSupport>] [-s=<srcDirName>]
                         [-t=<pingTimeout>] [-u=<debugServerUrl>] [-u:
                         file=<debugServerUrlFileName>]
                         [-x=<externalDataProcessorUrl>]
                         [--extension=<extensionName>=<path>]...
                         [--include-user=USER]... [--source=NAME=DIR]... [-a
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
  -s, --srcDir=<srcDirName> Directory with sources exported to xml
  -P, --projectDir=<projectDirName>
                            Directory with project
  -r, --removeSupport=<removeSupport>
                            Remove support values: NOT_EDITABLE,
                              EDITABLE_SUPPORT_ENABLED, NOT_SUPPORTED, NONE.
                              Default - NONE
      --config=<configFile> Coverage configuration file or directory with *.
                              json files
      --exclusion-report=<exclusionReportFile>
                            Write coverage exclusion report to JSON file
      --source=NAME=DIR     Source directory relative to -P; repeat for each
                              extension (empty name for main configuration)
      --extension=<extensionName>=<path>
                            Extension source directory relative to -P. Can be
                              repeated.
  -o, --out=<outputFile>    Output file name
  -f, --format=<outputFormat>
                            Output file format: GENERIC_COVERAGE, LCOV,
                              COBERTURA. Default - GENERIC_COVERAGE
  -p, --password            Dbgs password
      -p:env, --password:env=<passwordEnv>
                            Password environment variable name
  -n, --areanames[=<debugAreaNames>...]
                            Debug area names (not for general use!)
  -a, --autoconnectTargets[=<autoconnectTargets>...]
                            Autoconnect debug targets (not for general use!):
                              Unknown, Client, ManagedClient, WEBClient,
                              COMConnector, Server, ServerEmulation,
                              WEBService, HTTPService, OData, JOB, JobFileMode,
                              MobileClient, MobileServer, MobileJobFileMode,
                              MobileManagedClient, MobileManagedServer
  -t, --timeout=<pingTimeout>
                            Ping timeout. Default - 1000
      --verbose             If you need more logs. Default - false
      --opid=<opid>         Owner process PID
      --include-user=USER   Collect measures only from debug targets of this
                              infobase user (exact name); repeat for several
                              users. Replaces includeUsers of --config
  -h, --help                Show this help message and exit.
  -V, --version             Print version information and exit.
```

</details>

<details>
<summary>Параметры convert</summary>

```text
> Coverage41C convert --help
Usage: Coverage41C convert [-hV] [--verbose] -c=<inputRawXmlFile>
                           [--config=<configFile>] [-e=<extensionName>]
                           [--exclusion-report=<exclusionReportFile>]
                           [-f=<outputFormat>] [-o=<outputFile>]
                           [-P=<projectDirName>] [-r=<removeSupport>]
                           [-s=<srcDirName>] [-x=<externalDataProcessorUrl>]
                           [--extension=<extensionName>=<path>]...
                           [--source=NAME=DIR]...
Convert results from internal uuid-based format
  -o, --out=<outputFile>   Output file name
  -f, --format=<outputFormat>
                           Output file format: GENERIC_COVERAGE, LCOV,
                             COBERTURA. Default - GENERIC_COVERAGE
  -c, --convertFile=<inputRawXmlFile>
                           Input file name with RAW xml coverage data
  -s, --srcDir=<srcDirName>
                           Directory with sources exported to xml
  -P, --projectDir=<projectDirName>
                           Directory with project
  -r, --removeSupport=<removeSupport>
                           Remove support values: NOT_EDITABLE,
                             EDITABLE_SUPPORT_ENABLED, NOT_SUPPORTED, NONE.
                             Default - NONE
      --config=<configFile>
                           Coverage configuration file or directory with *.json
                             files
      --exclusion-report=<exclusionReportFile>
                           Write coverage exclusion report to JSON file
      --source=NAME=DIR    Source directory relative to -P; repeat for each
                             extension (empty name for main configuration)
      --extension=<extensionName>=<path>
                           Extension source directory relative to -P. Can be
                             repeated.
      --verbose            If you need more logs. Default - false
  -e, --extensionName=<extensionName>
                           Extension name
  -x, --externalDataProcessor=<externalDataProcessorUrl>
                           External data processor (or external report) url
  -h, --help               Show this help message and exit.
  -V, --version            Print version information and exit.
```

</details>

<details>
<summary>Параметры validate</summary>

```text
> Coverage41C validate --help
Usage: Coverage41C validate [-hV] [--strict] [--config=<configFile>]
                            [--exclusion-report=<exclusionReportFile>]
                            [-P=<projectDirName>] [-r=<removeSupport>]
                            [-s=<srcDirName>]
                            [--extension=<extensionName>=<path>]...
                            [--source=NAME=DIR]...
Validate coverage configuration and markers without measuring
  -s, --srcDir=<srcDirName>
                          Directory with sources exported to xml
  -P, --projectDir=<projectDirName>
                          Directory with project
  -r, --removeSupport=<removeSupport>
                          Remove support values: NOT_EDITABLE,
                            EDITABLE_SUPPORT_ENABLED, NOT_SUPPORTED, NONE.
                            Default - NONE
      --config=<configFile>
                          Coverage configuration file or directory with *.json
                            files
      --exclusion-report=<exclusionReportFile>
                          Write coverage exclusion report to JSON file
      --source=NAME=DIR   Source directory relative to -P; repeat for each
                            extension (empty name for main configuration)
      --extension=<extensionName>=<path>
                          Extension source directory relative to -P. Can be
                            repeated.
      --strict            Fail on warnings. Default - false
  -h, --help              Show this help message and exit.
  -V, --version           Print version information and exit.
```

</details>

<details>
<summary>Запуск тестов для разработчиков</summary>
<br>

> Укажите `EDT_LOCATION`, как описано в инструкции по установке, и запустите `./gradlew test`. Тесты сборщика используют локальную имитацию сервера отладки.
>
> Для интеграционного прогона с установленной платформой 1С, `dbgs`, OneScript и `vrunner` используйте `./gradlew integrationTest`.

</details>
