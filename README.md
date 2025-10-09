# README — jchat / Keycloak Operator (from scratch)

> Полный путь от «чистого» окружения до работающего Keycloak за Ingress в Minikube (WSL2/Windows и Linux/macOS). В конце — teardown и диагностика.

---

## 0) Софт (установить один раз)

**Обязательное:**

* Docker Desktop / Docker Engine
* kubectl ≥ 1.27
* minikube ≥ 1.32
* Helm ≥ 3.13
* yq (mikefarah, v4) и jq

Проверка версий:

```bash
kubectl version --client
minikube version
helm version
yq --version && jq --version
```

> WSL2/Windows: ставьте инструменты именно в той среде, где будете работать (рекомендуется WSL2 Ubuntu). Docker Desktop должен шарить Docker daemon в WSL2.

---

## 1) Чистый старт кластера

```bash
# полностью убрать старые профили/данные (по желанию)
minikube delete --all --purge

# запустить новый кластер (драйвер docker — по умолчанию)
minikube start
```

Если не используете Docker Desktop: выберите драйвер явно, например `--driver=docker` или `--driver=hyperv`.

---

## 2) Ingress как LoadBalancer + tunnel (надёжно для WSL2/Windows)

```bash
# включаем ingress-addon
minikube addons enable ingress

# переводим сервис контроллера в LoadBalancer (обычно addon делает это сам)
kubectl -n ingress-nginx patch svc ingress-nginx-controller \
  -p '{"spec":{"type":"LoadBalancer"}}'

# запускаем туннель в отдельной вкладке/окне и оставляем его работать
sudo -E minikube tunnel --cleanup=true --bind-address=127.0.0.1
```

Хосты (где открываете браузер):

```
127.0.0.1  keycloak.jchat.local identity.jchat.local
```

Проверьте, что у контроллера есть EXTERNAL-IP (не `<pending>`):

```bash
kubectl -n ingress-nginx get svc ingress-nginx-controller
```

> Альтернатива на время отладки: `kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80` и используйте `http://keycloak.jchat.local:8080/`.

---

## 3) Установка OLM (Operator Lifecycle Manager)

```bash
# неймспейсы OLM появятся автоматически
# scripted install (latest)
OLM_VERSION=$(curl -sL https://api.github.com/repos/operator-framework/operator-lifecycle-manager/releases/latest | jq -r .tag_name)
curl -L "https://github.com/operator-framework/operator-lifecycle-manager/releases/download/${OLM_VERSION}/install.sh" -o /tmp/olm-install.sh
bash /tmp/olm-install.sh "${OLM_VERSION}"

# проверить
kubectl -n olm get pods
kubectl -n olm get packagemanifests | head
```

> Если OLM уже установлен — этот шаг идемпотентен.

---

## 4) Развёртывание Keycloak Operator (через OLM)

Манифесты находятся в `deploy/k8s/operator/keycloak/`.

```bash
# создаём ns для оператора и подписку
kubectl apply -k deploy/k8s/operator/keycloak/

# проверить CSV/под операторов
kubectl -n keycloak-operator get csv,deploy,pods
```

> В `subscription.yaml` используется канал `fast`.

---

## 5) Базовые ресурсы окружения (без CR Keycloak)

Манифесты: `deploy/k8s/` (секреты, конфиги, ингрессы, **но без** CR Keycloak — применим позже).

```bash
kubectl apply -k deploy/k8s/
```

> Если у вас в корневом `kustomization.yaml` осталась строка `- cr/keycloak` — удалите её, чтобы CR не применялся до БД.

---

## 6) Базы данных (Helm)

Используем чарт groundhog2k/postgres для приложения и для Keycloak.

```bash
helm repo add groundhog2k https://groundhog2k.github.io/helm-charts/ || true

# Postgres для приложения
helm upgrade --install pg groundhog2k/postgres \
  -n jchat -f deploy/k8s/values/values-pg.yaml \
  --wait --timeout 10m

# Postgres для Keycloak
helm upgrade --install kc-pg groundhog2k/postgres \
  -n jchat -f deploy/k8s/values/values-kc-pg.yaml \
  --wait --timeout 10m

# фактическое имя сервиса для KC-БД (часто kc-pg-postgres)
kubectl -n jchat get svc -l app.kubernetes.io/instance=kc-pg
```

Запомните имя сервиса БД Keycloak (далее предполагаем `kc-pg-postgres`).

---

## 7) Применение Keycloak CR (v2alpha1)

Файл: `deploy/k8s/cr/keycloak/keycloak-cr.yaml` — **обязательно** формат API `k8s.keycloak.org/v2alpha1`.

Минимальные требования к полям (сверьте с вашим YAML):

```yaml
spec:
  instances: 1

  http:
    httpEnabled: true
  hostname:
    hostname: keycloak.jchat.local
    port: 80
    strict: false

  ingress:
    enabled: false

  bootstrapAdmin:
    user:
      secret: kc-admin-auth   # секрет с ключами username и password

  db:
    vendor: postgres
    host: kc-pg-postgres      # имя сервиса БД KC
    port: 5432
    database: keycloak        # ЯВНОЕ имя БД, без ${...}
    usernameSecret:
      name: kc-pg-auth        # или kc-pg-postgres / POSTGRES_USER
      key: USERDB_USER
    passwordSecret:
      name: kc-pg-auth        # или kc-pg-postgres / POSTGRES_PASSWORD
      key: USERDB_PASSWORD

  additionalOptions:
    - { name: proxy, value: edge }
    - { name: proxy-headers, value: xforwarded }
```

Применение и ожидание готовности:

```bash
kubectl -n jchat apply -f deploy/k8s/cr/keycloak/keycloak-cr.yaml
kubectl -n jchat wait --for=condition=Ready keycloak/keycloak --timeout=10m \
|| kubectl -n jchat rollout status statefulset/keycloak --timeout=10m
```

Проверка статуса CR:

```bash
kubectl -n jchat get keycloak keycloak -o jsonpath='{range .status.conditions[*]}{.type}={.status} {.message}{"\n"}{end}'
```

---

## 8) Импорт realm (из большого JSON, без inlining в YAML)

Файлы:

* `deploy/k8s/cr/keycloak/realm-app/realm-export.json` — ваш экспорт realm
* `deploy/k8s/cr/keycloak/keycloak-realm-import.tpl.yaml` — шаблон CR

Шаблон:

```yaml
apiVersion: k8s.keycloak.org/v2alpha1
kind: KeycloakRealmImport
metadata:
  name: realm-app-import
  namespace: jchat
spec:
  keycloakCRName: keycloak
  realm: {}
```

Применение (yq v4 — mikefarah):

```bash
yq -o=y '.spec.realm = load("deploy/k8s/cr/keycloak/realm-app/realm-export.json")' \
   deploy/k8s/cr/keycloak/keycloak-realm-import.tpl.yaml \
| kubectl apply -f -
```

> Важно: `KeycloakRealmImport` **создаёт** новый realm и **не обновляет** существующий. Для dev-цикла удаляйте старый realm или меняйте имя.

---

## 9) Ingress для Keycloak

Файл: `deploy/k8s/ingress/keycloak-ing.yaml`

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: keycloak
  namespace: jchat
  annotations:
    nginx.ingress.kubernetes.io/proxy-body-size: "20m"
spec:
  ingressClassName: nginx
  rules:
    - host: keycloak.jchat.local
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: keycloak-service   # имя вашего svc
                port:
                  name: http             # порт по имени (в svc это 8080)
```

Применить и проверить:

```bash
kubectl -n jchat apply -f deploy/k8s/ingress/keycloak-ing.yaml
kubectl -n jchat get ing
kubectl -n jchat describe ing keycloak | sed -n '1,40p'
```

Открыть:

```
http://keycloak.jchat.local/admin/
```

Учётка админа:

```bash
kubectl -n jchat get secret kc-admin-auth -o jsonpath='{.data.username}{"\n"}{.data.password}{"\n"}' | base64 -d; echo
```

---

## 10) Частые проблемы и быстрые проверки

**(а) KC не видит БД / FATAL auth failed / DB not exist**

```bash
kubectl -n jchat get svc | grep kc-pg
kubectl -n jchat get endpoints kc-pg-postgres -o wide
kubectl -n jchat get secret kc-pg-postgres -o jsonpath='{.data.POSTGRES_USER}{"\n"}{.data.POSTGRES_DB}{"\n"}' | base64 -d
```

— В CR `db.host` = фактический сервис, `db.database` = явная строка, креды берём из секрета (`kc-pg-auth` или секрет чарта `kc-pg-postgres`).

**(б) Ingress не отвечает**

```bash
kubectl -n ingress-nginx get svc ingress-nginx-controller
# EXTERNAL-IP не должен быть <pending> (держите minikube tunnel)

kubectl -n jchat describe ing keycloak | tail -n+1 | sed -n '1,80p'
```

— Если EXTERNAL-IP `<pending>` → tunnel не запущен/нет прав.

**(в) Редирект на :8080**
— В CR задайте `spec.hostname.port: 80`, включите `spec.http.httpEnabled: true` и прокси-опции (`proxy=edge`, `proxy-headers=xforwarded`).

**(г) Ждать готовности**

```bash
kubectl -n jchat wait --for=condition=Ready keycloak/keycloak --timeout=10m \
|| kubectl -n jchat rollout status statefulset/keycloak --timeout=10m
```

---

## 11) Полный teardown (с чисткой)

Минимальный «снос» окружения jchat:

```bash
# импорт (если создавали)
kubectl -n jchat delete keycloakrealmimport realm-app-import --ignore-not-found

# Keycloak CR
kubectl -n jchat delete keycloak keycloak --ignore-not-found --wait=true

# Базы
helm -n jchat uninstall kc-pg || true
helm -n jchat uninstall pg || true

# Остальные манифесты
kubectl delete -k deploy/k8s/ || true

# (опционально) оператор
kubectl delete -k deploy/k8s/operator/keycloak/ || true

# (опционально) namespace
a= jchat; kubectl delete ns $a --ignore-not-found
```

Полный сброс Minikube:

```bash
pkill -f "minikube tunnel" || true
minikube delete --all --purge
minikube start
```

---

## 12) Работа через Makefile (если используете)

Быстрый сценарий:

```bash
# поднять инфраструктуру
make infra

# Поднять сервисы
make app

# запустить все сразу (если в realm зашит секрет):
make dev-app
```


Снос:

```bash
make down        # удалить Keycloak/БД/ресурсы, оставить ns/OLM
# или
make down-all    # снести всё включая оператор и namespace
```

---

### Готово

Открывайте `http://keycloak.jchat.local/admin/`, логин — из секрета `kc-admin-auth`. Если что-то идёт не так — смотрите раздел «Частые проблемы и быстрые проверки».
