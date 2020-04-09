# Coverage41C
Замеры покрытия для платформы 1С: Предприятие 8

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
