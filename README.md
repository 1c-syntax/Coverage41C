# Coverage41C
Замеры покрытия для платформы 1С: Предприятие 8

Сценарий использования:

0) Устанавливаем EDT (для работы программы нужны как минимум его библиотеки ```com._1c.g5.v8.dt.debug.core_*.jar```, ```com._1c.g5.v8.dt.debug.model_*.jar```). Если держать EDT на данной машине затруднительно, из можно скопировать в любую папку, установить параметр окружения EDT_LOCATION в данную папку и удалить EDT.
1) Скачиваем последнюю версию со страницы https://github.com/proDOOMman/Coverage41C/releases или устанавливаем через ```choco install Coverage41C```
2) Включаем http-отладку на сервере 1С-Предприятия потём добавления к флагу -debug флага -http (или добавляем флаги ```/debug -http -attach /debuggerURL «адрес отладчика»``` в строку запуска файловой базы, см. https://its.1c.ru/db/v837doc#bookmark:adm:TI000000495)
3) Проверяем что dbgs.exe (https://its.1c.ru/db/edtdoc/content/197/hdoc/_top/dbgs) запустился и работает. Для этого в браузере открываем его, адрес по умолчанию http://127.0.0.1:1550/. В случае успеха выдолжны увидеть сообщение "... it works!".
4) Выгружаем исходники конфигурации или расширения в файлы.
5) Запускаем анализ покрытия командой ```Coverage41C -i <ИмяИнформационнойБазыВКластере> -s <ПутьКИсходникам> -o <ИмяВыходногоФайлаПокрытия> -e <ИмяРасширения>```. Для файловой базы нужно указать адрес отладчика и предопределённое имя информационной базы ```-i DefAlias -u http://127.0.0.1:<Порт>```.
    > В случае если исходники лежат не в корне проекта, необходимо указать путь к проекту `-P <ПутьКПроекту>`.
6) Выполняем тесты
7) Останавливаем программу нажатием Ctrl+C в окне терминала или командой ```Coverage41C -a stop -i <ИмяИнформационнойБазыВКластере> -u http://127.0.0.1:<Порт>```
8) Полученный файл в формате genericCoverage.xml загружаем в SonarQube.

```cmd
Usage: Coverage41C [-hV] [-p] [-a=<commandAction>] [-e=<extensionName>]
                   -i=<infobaseAlias> [-o=<outputFile>] [-r=<removeSupport>]
                   [-s=<srcDirName>] [-t=<pingTimeout>] [-u=<debugServerUrl>]
                   [-n=<debugAreaNames>]...
Make measures from 1C:Enterprise and save them to genericCoverage.xml file
  -a, --action=<commandAction>
                           Action: start, stop. Default - start
  -i, --infobase=<infobaseAlias>
                           InfoBase name
  -e, --extensionName=<extensionName>
                           Extension name
  -s, --srcDir=<srcDirName>
                           Directory with sources exported to xml
  -P, --projectDir
                           Directory with project
  -o, --out=<outputFile>   Output file name
  -u, --debugger=<debugServerUrl>
                           Debugger url. Default - http://127.0.0.1:1550/
  -p, --password           Dbgs password
  -n, --areanames=<debugAreaNames>
                           Debug area names (not for general use!)
  -t, --timeout=<pingTimeout>
                           Ping timeout. Default - 1000
  -r, --removeSupport=<removeSupport>
                           Remove support values: NOT_EDITABLE,
                             EDITABLE_SUPPORT_ENABLED, NOT_SUPPORTED, NONE.
                             Default - NONE
  -h, --help               Show this help message and exit.
  -V, --version            Print version information and exit.
```
