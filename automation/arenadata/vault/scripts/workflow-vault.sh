#!/usr/bin/env bash

# Set environment variables
export VAULT_ADDR='https://0.0.0.0:8200'
export VAULT_SKIP_VERIFY='true'
export ADB_CLUSTER_NAME=${ADB_CLUSTER_NAME:-adb-it}

# Handler on vault stop to clean up generated credentials
termination_handler() {
    echo "Termination signal received. Cleaning up credentials"
    rm -f /env/*
    exit 0
}

trap 'termination_handler' SIGTERM

# Start Vault server in the background
vault server -config /scripts/vault.hcl &
sleep 5

# Initialize Vault and capture the output
vault operator init -key-shares=3 -key-threshold=2 -format=json > init_output.json

# Check if the initialization was successful
if [ $? -ne 0 ]; then
  echo "Vault initialization failed"
  exit 1
fi

# Parse unseal keys and root token from the JSON output
UNSEAL_KEYS=$(jq -r ".unseal_keys_b64[]" init_output.json)
ROOT_TOKEN=$(jq -r ".root_token" init_output.json)

if [ -z "$UNSEAL_KEYS" ] || [ -z "$ROOT_TOKEN" ]; then
  echo "Failed to parse unseal keys or root token"
  exit 1
fi

# Unseal Vault
for key in ${UNSEAL_KEYS}; do
  vault operator unseal ${key}
  if [ $? -ne 0 ]; then
    echo "Failed to unseal Vault with key ${key}"
    exit 1
  fi
done

# Save root token for later use
echo ${ROOT_TOKEN} > root_token.txt

# Export Vault root token
export VAULT_TOKEN=${ROOT_TOKEN}

# Enable KV secrets engine
vault secrets enable -path=secret kv-v2
if [ $? -ne 0 ]; then
  echo "Failed to enable KV secrets engine"
  exit 1
fi

# Enable the AppRole auth method
vault auth enable approle
if [ $? -ne 0 ]; then
  echo "Failed to enable AppRole auth method"
  exit 1
fi

# Create a policy adb-only
vault policy write adb-only /scripts/adb-only-policy.hcl
if [ $? -ne 0 ]; then
  echo "Failed to write adb-only policy"
  exit 1
fi

# Create an AppRole with the policy adb-only
vault write auth/approle/role/pxf policies="adb-only"
if [ $? -ne 0 ]; then
  echo "Failed to create AppRole pxf"
  exit 1
fi

# Retrieve the RoleID and SecretID for the AppRole
ROLE_ID=$(vault read -field=role_id auth/approle/role/pxf/role-id)
SECRET_ID=$(vault write -field=secret_id -f auth/approle/role/pxf/secret-id)

# Save the RoleID and SecretID for later use
echo ${ROLE_ID} > /env/role_id
echo ${SECRET_ID} > /env/secret_id

# PXF secrets
yq -o=json eval /secrets/pxf/pxf-service.yml | vault kv put -mount=secret adb/"$ADB_CLUSTER_NAME"/service/pxf -

vault token create -policy="adb-only"

echo "Vault setup completed successfully"

wait
