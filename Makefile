.PHONY: infra app down down-all down-kc-operator down-kafka-operator get-pods upgrade \
	ingress-enable ingress-apply helm-up helm-up-wait build push logs \
	hosts-win hosts-win-remove realm-import-apply

# =============================
#  Конфигурация по умолчанию
# =============================
# Репозиторий Docker-образов
REG_IDENTITY?=jchat/identity
# тег образа по умолчанию
TAG_IDENTITY?=identity-$(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
# namespace k8s
NS?=jchat
REALM_JSON:=deploy/k8s/cr/keycloak/realm-app/realm-export.json
REALM_TPL:=deploy/k8s/cr/keycloak/keycloak-realm-import.tpl.yaml

# === Цвета/форматирование (ANSI). Отключаются, если NO_COLOR=1 ===
ifeq ($(NO_COLOR),1)
  C_RESET:=
  C_BOLD:=
  C_RED:=
  C_GRN:=
  C_YEL:=
  C_BLU:=
else
  C_RESET:=\033[0m
  C_BOLD:=\033[1m
  C_RED:=\033[31m
  C_GRN:=\033[32m
  C_YEL:=\033[33m
  C_BLU:=\033[34m
endif

# printf-обёртка (без лишних "echo -e")
define say
	@printf "%b\n" "$(1)"
endef

# Покраска хелперы
ok=$(call say,$(C_GRN)✔$(C_RESET) $(1))
warn=$(call say,$(C_YEL)⚠ $(1)$(C_RESET))
err=$(call say,$(C_RED)✖ $(1)$(C_RESET))
step=$(call say,$(C_BLU)[$(1)]$(C_RESET) $(2))
hdr=$(call say,$(C_BOLD)$(1)$(C_RESET))


# =============================
#  0) Утилиты
# =============================
get-pods: ## Показать поды в namespace
	kubectl -n $(NS) get pods

logs: ## Логи identity-service (follow)
	kubectl -n $(NS) logs deploy/identity-service -f --max-log-requests=1

realm-import-apply: ## Конвертация реалма
	yq -o=y '.spec.realm = load("$(REALM_JSON)")' $(REALM_TPL) | kubectl apply -f -

# =============================
#  1) Инфраструктура (PG, Keycloak, Kafka, секреты)
# =============================
infra: ## Развернуть секреты и инфраструктуру (PG, KC, Kafka)
	# 0) гарантируем существование namespace
	kubectl get ns $(NS) >/dev/null 2>&1 || kubectl create ns $(NS)

	# 1) оператор (OLM): ставим/обновляем операторы
	kubectl apply -k deploy/k8s/operator/keycloak/
	kubectl apply -k deploy/k8s/operator/strimzi/

	# 2) ресурсы окружения (secrets, CR и т.д.)
	kubectl apply -k deploy/k8s/

	# 3) Postgres (основной)
	helm repo add groundhog2k https://groundhog2k.github.io/helm-charts/
	helm upgrade --install pg groundhog2k/postgres \
	  -n $(NS) \
	  -f deploy/k8s/values/values-pg.yaml \
	  --wait --timeout 10m

	# 4) Postgres для Keycloak
	helm upgrade --install kc-pg groundhog2k/postgres \
	  -n $(NS) \
	  -f deploy/k8s/values/values-kc-pg.yaml \
	  --wait --timeout 10m

	# 5) Применяем CR
	kubectl -n $(NS) apply -f deploy/k8s/cr/keycloak/keycloak-cr.yaml

	# 6) Ждём Keycloak (оператор поднимет инстанс по CR)
	kubectl -n $(NS) wait --for=condition=Ready keycloak/keycloak --timeout=10m || \
    kubectl -n $(NS) rollout status statefulset/keycloak --timeout=10m

	# 7) Импорт реалма
	$(MAKE) realm-import-apply

	# 8) Проброс ingress
	$(MAKE) ingress-apply

	# 9) Kafka cluster (Strimzi, KRaft: 1 controller + 1 broker)
	$(MAKE) kafka-nodepools-apply
	$(MAKE) kafka-apply
	$(MAKE) kafka-wait

# =============================
#  2) Сборка/деплой приложения
# =============================
# ВАЖНО: docker build запускать из корня репозитория (контекст = '.')
build: ## Собрать Docker-образы из корня
	docker build -t $(REG_IDENTITY):$(TAG_IDENTITY) -f services/identity-service/Dockerfile .

push: ## Запушить образы
	minikube image load $(REG_IDENTITY):$(TAG_IDENTITY)

app: build push ## Сборка+публикация образов и деплой чарта
	helm upgrade --install identity-service deploy/k8s/charts/identity-service -n $(NS) \
	  --set image.repository=$(REG_IDENTITY) --set image.tag=$(TAG_IDENTITY) \
	  --wait --timeout 10m

app-full-reload:
	helm -n $(NS) uninstall identity-service || true
	kubectl apply -k deploy/k8s/
	$(MAKE) app

# =============================
#  3) Ingress (nginx)
# =============================
ingress-enable: ## Включить ingress (minikube addon)
	minikube addons enable ingress

ingress-apply: ## Применить Ingress-манифесты (Keycloak, Identity)
	kubectl -n $(NS) apply -f deploy/k8s/ingress/keycloak-ing.yaml
	kubectl -n $(NS) apply -f deploy/k8s/ingress/identity-ing.yaml

# Альтернативные helm-апгрейды (без/с ожиданием) — удобны, когда меняешь values/env
helm-up: ## Helm upgrade/install без ожидания
	helm upgrade --install identity-service deploy/k8s/charts/identity-service -n $(NS) \
	  --reuse-values \
	  --set image.repository=$(REG_IDENTITY) \
	  --set image.tag=$(TAG_IDENTITY)

helm-up-wait: ## Helm upgrade/install с ожиданием
	helm upgrade --install identity-service deploy/k8s/charts/identity-service -n $(NS) \
	  --reuse-values \
	  --wait --timeout 10m \
	  --set image.repository=$(REG_IDENTITY) \
	  --set image.tag=$(TAG_IDENTITY)

# =============================
#  5) KAFKA
# =============================
# Apply Kafka NodePools (KRaft: controller + broker)
kafka-nodepools-apply:
	kubectl -n $(NS) apply -f deploy/k8s/cr/kafka/nodepool-controller.yaml
	kubectl -n $(NS) apply -f deploy/k8s/cr/kafka/nodepool-broker.yaml

# Apply Kafka cluster (KRaft)
kafka-apply:
	kubectl -n $(NS) apply -f deploy/k8s/cr/kafka/kafka-cr.yaml

# ждём, пока CR Kafka станет Ready
kafka-wait:
	kubectl -n $(NS) wait --for=condition=Ready kafka/kafka --timeout=10m || \
	( echo "Kafka CR not Ready yet; waiting for first pods..." && \
	  kubectl -n $(NS) wait pod -l strimzi.io/cluster=kafka --for=condition=Ready --timeout=10m )

kafka-client-clean:
	kubectl -n $(NS) delete pod kafka-client --ignore-not-found

# =============================
#  6) Снос окружения
# =============================
down: ## Удалить Keycloak, БД и окружение (namespace и OLM остаются)
	# 0) импорт realm (если применяли через realm-import-apply)
	kubectl -n $(NS) delete keycloakrealmimport realm-app-import --ignore-not-found

	# 1) Keycloak (CR) — гасим инстанс через оператор
	kubectl -n $(NS) delete keycloak keycloak --ignore-not-found --wait=true
	# подождём, пока все поды keycloak уйдут
	kubectl -n $(NS) wait --for=delete pod -l app.kubernetes.io/name=keycloak --timeout=120s

	# 2) БД (Helm)
	helm -n $(NS) uninstall kc-pg || true
	helm -n $(NS) uninstall pg || true
	helm -n $(NS) uninstall kafka || true
	helm -n $(NS) uninstall identity-service || true  # если нужно сносить и приложение

	# 3) Остальные ресурсы окружения (секреты/CM/ингрессы и т.п.)
	kubectl delete -k deploy/k8s/ || true

# Полный снос: + оператор и namespace
down-all: down down-kc-operator down-kafka-operator
	kubectl delete ns $(NS) --ignore-not-found

# Снос оператора Keycloak (OLM)
down-kc-operator:
	kubectl delete -f deploy/k8s/operator/keycloak/subscription.yaml --ignore-not-found
	kubectl delete -f deploy/k8s/operator/keycloak/operatorgroup.yaml --ignore-not-found
	kubectl delete -f deploy/k8s/operator/keycloak/namespace.yaml --ignore-not-found

# Снос оператора Kafka (OLM)
down-kafka-operator:
	kubectl -n $(NS) delete -f deploy/k8s/cr/kafka/kafka-cr.yaml --ignore-not-found
	kubectl -n $(NS) delete -f deploy/k8s/cr/kafka/nodepool-broker.yaml --ignore-not-found
	kubectl -n $(NS) delete -f deploy/k8s/cr/kafka/nodepool-controller.yaml --ignore-not-found

## 7) Композитные цели «с нуля → готово к Postman»
# Полный подъём окружения: инфраструктура (PG/KC/Kafka) → сборка/деплой сервиса → Ingress → hosts на Windows.
# Запускать из WSL2. Для hosts потребуется PowerShell с правами администратора (или запусти `wsl make dev-up`
# из Admin PowerShell).

dev-up:
	$(call step,0/6,Проверяем minikube...)
	@minikube status >/dev/null 2>&1 || minikube start
	$(call step,1/6,Включаем ingress controller (nginx)...)
	$(MAKE) ingress-enable
	$(call step,2/6,Разворачиваем инфраструктуру (PG/Keycloak/Kafka/секреты) в namespace $(NS)...)
	$(MAKE) infra
	$(call step,3/6,Публикация образа и деплой чарта identity-service...)
	$(MAKE) app
	$(call step,4/6,Применяем Ingress-манифесты (Keycloak/Identity)...)
	$(MAKE) ingress-apply
	$(call ok, Готово, адреса:)
	$(call say, $(C_BOLD)hosts$(C_RED): 127.0.0.1 keycloak.jchat.local identity.jchat.local)
	$(call say, $(C_BOLD)tulnnel$(C_RED): 127.0.0.1 keycloak.jchat.local identity.jchat.local)
	$(call say, $(C_BOLD)Keycloak$(C_RESET):  http://keycloak.jchat.local)
	$(call say, $(C_BOLD)Identity$(C_RESET):  http://identity.jchat.local/actuator/health)