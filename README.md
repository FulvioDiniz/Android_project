# Quadros — fase 1

Cadastro de quadro, sessão por perfil e lista no Galaxy Tab A11+. O banco do servidor é um arquivo SQLite. Ainda não há PDF nem revisão.

## API

```bash
cd api
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Ensaios: `cd api && .venv/bin/pytest`.

Logins semeados, senha igual ao perfil: `ana.ribeiro` / `engenharia`, `carlos.mota` / `montagem`, `helena.dias` / `supervisao`, `rita.sol` / `admin`.

O tablet desta fase usa o identificador `tablet-fase-1`, já liberado. Outro aparelho nasce pendente e não entra.

## Painel

```bash
cd painel
npm install
npm run dev
```

Abre em `http://localhost:5173`. A engenharia cadastra. Montagem recebe recusa no painel.

## Tablet

Abra a pasta `android` no Android Studio e rode no Galaxy Tab A11+ físico. Na tela de entrada, o servidor é o endereço da máquina da API na rede da fábrica, por exemplo `http://192.168.0.10:8000`.

O HTTP desta fase é para o ensaio na rede interna. TLS entra quando houver certificado da fábrica.
