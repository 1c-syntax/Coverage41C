# Coverage41C

Публикация пакета https://chocolatey.org/

## Сборка пакета

```powershell
choco pack --version $env:CI_COMMIT_TAG
```

## Публикация в репозитории

```powershell
choco push --source $env:CHOCO_HOST --api-key $env:CHOCO_APIKEY
```

## Установка

```powershell
# Локально
choco install Coverage41C.$($env:CI_COMMIT_TAG).nupkg
# Из репозитория
choco install coverage41c --source $env:CHOCO_HOST
```
