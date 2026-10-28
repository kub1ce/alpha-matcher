# Alpha Matcher

## Демо-интерфейс

Нужен JDK 25. Профиль `demo` запускает Vaadin без подключения к MySQL и без миграций Flyway. В PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'demo'
.\mvnw.cmd spring-boot:run
```

Откройте `http://localhost:8080/login`. Выберите одну из двух демонстрационных ролей. Логин и пароль можно изменить; для входа оба поля должны быть заполнены. Это только демонстрационный вход, без проверки учетных данных. Обычный запуск приложения использует прежние настройки БД.

## Материалы

- [Модель базы данных](docs/database/README.md)
- [Сущности и поля](docs/database/entities.md)
- [ER-диаграмма](docs/database/er-diagram.md)
- [SQL-схема MySQL](database/schema.sql)
