#!/usr/bin/env bash

# Main
export IFS=","

dbid=2 # dbid=1 is a master segment
segid=0

is_mirrored=$DOCKER_GP_WITH_MIRROR
primary_segments_per_host=$DOCKER_GP_PRIMARY_SEGMENTS_PER_HOST

# Wait Vault service
if [[ "$PXF_VAULT_ENABLED" = true ]]; then
    echo "--------------------------"
    echo "Wait Vault service for PXF"
    echo "--------------------------"
    role_id_file="/vault/env/role_id"
    secret_id_file="/vault/env/secret_id"
    while [ ! -f "$role_id_file" ] || [ ! -f "$secret_id_file" ]; do
      echo "Waiting for vault init approle envs"
      sleep 1
    done
    # Read the role_id and secret_id from the shared volume
    export PXF_VAULT_ROLE_ID=$(cat "$role_id_file")
    export PXF_VAULT_SECRET_ID=$(cat "$secret_id_file")
    echo "Vault environment were initialized successfully"
fi

# Base config
CONFIG="ARRAY_NAME='Demo Greenplum Cluster'
TRUSTED_SHELL=ssh
CHECK_POINT_SEGMENTS=8
ENCODING=UNICODE
SEG_PREFIX=seg
HEAP_CHECKSUM=on
HBA_HOSTNAMES=0
QD_PRIMARY_ARRAY=$DOCKER_GP_MASTER_SERVER~$DOCKER_GP_MASTER_SERVER~5432~/data1/master/gpseg-1~1~-1
declare PRIMARY_ARRAY=("

# Add primary segments to the config
for server in $DOCKER_GP_SEGMENT_SERVERS
do
  primary_port=6001
  for (( i=1 ; i<=$primary_segments_per_host ; i++ ));
  do
      echo -e -n "primary: host=$server; dbid=$dbid; segid=$segid, port=$primary_port\n"
      CONFIG+="$server~$server~$primary_port~/data1/primary/gpseg$segid~$dbid~$segid "
      ((dbid++))
      ((segid++))
      ((primary_port++))
  done
done
CONFIG+=")"

# Add mirror segments to the config
if [ -z "$is_mirrored" ] || [ "$is_mirrored" == "true" ]; then
  ((segid--))
  CONFIG+=" declare MIRROR_ARRAY=("
  for server in $DOCKER_GP_SEGMENT_SERVERS
  do
    mirror_port=7001
    for (( i=1 ; i<=$primary_segments_per_host ; i++ ));
    do
        echo -e -n "mirror : host=$server; dbid=$dbid; segid=$segid, port=$mirror_port\n"
        CONFIG+="$server~$server~$mirror_port~/data1/mirror/gpseg$segid~$dbid~$segid "
        ((dbid++))
        ((mirror_port++))
        ((segid--))
    done
  done
  CONFIG+=")"
  echo "------------------------------------"
  echo "Run cluster with mirrors. Config is:"
  echo "------------------------------------"
  echo -e "$CONFIG"
else
  echo "---------------------------------------"
  echo "Run cluster without mirrors. Config is:"
  echo "---------------------------------------"
  echo -e "$CONFIG"
fi

BASH_PROFILE="export PGPORT=5432
  export MASTER_DATA_DIRECTORY=/data1/master/gpseg-1
  source /usr/local/greenplum-db-devel/greenplum_path.sh
  export GPHOME=/usr/local/greenplum-db-devel
  export JAVA_HOME=\$(readlink -f /usr/bin/java | sed 's:bin/java::')
  export PXF_HOME=/usr/local/greenplum-db-devel/pxf
  export GPHD_ROOT=/home/gpadmin/workspace/singlecluster
  export TEST_HOME=/home/gpadmin/workspace/pxf/automation
  # Maven variables
  export M2_HOME=/opt/maven
  export MAVEN_HOME=/opt/maven
  PATH=/usr/lib/gpdb/bin/:/usr/local/greenplum-db-devel/bin/:/usr/local/greenplum-db-devel/pxf/bin:/opt/maven/bin:\$PATH
  export PATH"

# Run sshd
bash -c "/usr/sbin/sshd"

# Change the owner
chown -R gpadmin:gpadmin /home/gpadmin/.m2/

# Get ssh public keys of hosts
echo "----------------------------"
echo "Get ssh public keys of hosts"
echo "----------------------------"
keys=()
max_iterations=10
wait_seconds=3
iterations=0

servers=()
for server in $DOCKER_GP_CLUSTER_HOSTS; do
  servers+=("$server")
done

while true
do
  ((iterations++))
  echo "Get public key. Attempt $iterations"
  status=0
  toremove=()
  for server in "${servers[@]}"
  do
      echo "Get public key for $server"
      key=$(ssh-keyscan -t ssh-rsa $server)
      if [ $? -ne 0 ] ||  [[ $key != *"ssh-rsa"* ]]; then
        echo "Server $server doesn't have the public key yet. We will try again..."
        status=1
        break
      else
        echo "Add key for server $server"
        keys+=("$key")
        toremove+=("$server")
      fi
  done
  if [ $status -eq 0 ]; then
    echo "All ADB servers have public key"
    break
  elif [ "$iterations" -ge "$max_iterations" ]; then
    echo "Error to get public key for some ADB server after $max_iterations tries. Exit from script!"
    exit 1
  else
    for server in "${toremove[@]}"; do
      for i in "${!servers[@]}"; do
        if [[ ${servers[i]} = "$server" ]]; then
          unset 'servers[i]'
        fi
      done
    done
    echo "The following servers don't have the key yet: ${servers[*]}"
    echo "Wait $wait_seconds seconds and try again to get public key"
    sleep $wait_seconds
  fi
done

# Create config files
echo "----------------------------------------------"
echo "Copy keys, set bash profile and create configs"
echo "----------------------------------------------"
for key in "${keys[@]}"
do
  bash -c "echo $key >> /home/gpadmin/.ssh/known_hosts"
done
bash -c "cat /root/.ssh/id_rsa > /home/gpadmin/.ssh/id_rsa && cat /root/.ssh/id_rsa.pub > /home/gpadmin/.ssh/id_rsa.pub && cat /root/.ssh/authorized_keys > /home/gpadmin/.ssh/authorized_keys"
bash -c "echo \"$CONFIG\" > /home/gpadmin/gpdb_src/gpAux/gpdemo/create_cluster.conf"
bash -c "echo \"$BASH_PROFILE\" > /home/gpadmin/.bash_profile && echo \"$BASH_PROFILE\" > /home/gpadmin/.bashrc &&
 chown gpadmin:gpadmin /home/gpadmin/.bash_profile && chown gpadmin:gpadmin /home/gpadmin/.bashrc"

# Create linux cgroup for GP
echo "--------------------------"
echo "Create linux cgroup for GP"
echo "--------------------------"
bash -c "mkdir -p /sys/fs/cgroup/{memory,cpu,cpuset,cpuacct}/gpdb"
bash -c "chmod -R 777 /sys/fs/cgroup/{memory,cpu,cpuset,cpuacct}/gpdb"
bash -c "chown -R gpadmin:gpadmin /sys/fs/cgroup/{memory,cpu,cpuset,cpuacct}/gpdb"

echo "-----------------------------------"
echo "Remove sudo permission from gpadmin"
echo "-----------------------------------"
bash -c "sed -i 's/gpadmin ALL = NOPASSWD : ALL/# gpadmin ALL = NOPASSWD : ALL/g' /etc/sudoers"

# Install cluster
if [ "$HOSTNAME" == "$DOCKER_GP_MASTER_SERVER" ]; then
  if ! [ -d "/data1/master/gpseg-1" ]; then
    echo "----------------------------------"
    echo "Run Greenplum cluster installation"
    echo "----------------------------------"

    echo "--------------------"
    echo "Check SSH connection"
    echo "--------------------"
    max_iterations=60
    wait_seconds=5
    iterations=0
    while true
    do
      ((iterations++))
      echo "Try to connect to the hosts by ssh. Attempt $iterations"
      all_available=1
      for server in $DOCKER_GP_CLUSTER_HOSTS
      do
        status=$(sudo -H -u gpadmin bash -c "ssh -o PasswordAuthentication=no $server 'exit'")
        if [ $? -eq 0 ]; then
          echo "Server $server is available by ssh"
        else
          echo "Failed to connect to $server by ssh"
          all_available=0
        fi
      done
      if [ $all_available -eq 1 ]; then
        echo "All ADB servers are available by ssh"
        break
      elif [ "$iterations" -ge "$max_iterations" ]; then
        echo "Failed to connect to some ADB server by ssh after $max_iterations tries. Exit from script!"
        exit 1
      fi
    sleep $wait_seconds
    done

    echo "-------------------------"
    echo "Install Greenplum cluster"
    echo "-------------------------"
    sudo -H -u gpadmin bash -c "source /home/gpadmin/.bash_profile &&
        /usr/local/greenplum-db-devel/bin/gpinitsystem -a -I /home/gpadmin/gpdb_src/gpAux/gpdemo/create_cluster.conf -l /home/gpadmin/gpAdminLogs"

    # Check cluster
    echo "-------------------------------------"
    echo "Check connection to Greenplum cluster"
    echo "-------------------------------------"
    result="$( sudo -H -u gpadmin bash -c "source /home/gpadmin/.bash_profile && /usr/local/greenplum-db-devel/bin/psql -d postgres -Atc 'SELECT 1;'" )"
    if [ "${result}" == "1" ]; then
      echo "--------------------------------------------"
      echo "Fantastic!!! Greenplum cluster is available!"
      echo "--------------------------------------------"
      if ! [[ -z "$DOCKER_GP_STANDBY_SERVER" ]]; then
            # Activate standby master server
            echo "------------------------------"
            echo "Activate standby master server"
            echo "------------------------------"
            sudo -H -u gpadmin bash -c "source /home/gpadmin/.bash_profile && /usr/local/greenplum-db-devel/bin/gpinitstandby -a -s $DOCKER_GP_STANDBY_SERVER"
      fi
    else
      echo "-------------------------------------"
      echo "Error to connect to Greenplum cluster"
      echo "-------------------------------------"
      exit 1;
    fi

    echo "---------------------------------------------------"
    echo "Configuration and installation Greenplum extensions"
    echo "---------------------------------------------------"
    sudo -H -u gpadmin bash -c "source /home/gpadmin/.bash_profile &&
        psql -d postgres -Atc 'CREATE EXTENSION IF NOT EXISTS pxf;' &&
        psql -d postgres -Atc 'CREATE EXTENSION IF NOT EXISTS pxf_fdw;' &&
        echo 'local all testuser trust' >> /data1/master/gpseg-1/pg_hba.conf &&
        echo 'host all gpadmin 0.0.0.0/0 trust' >> /data1/master/gpseg-1/pg_hba.conf &&
        echo 'host all all 0.0.0.0/0 md5' >> /data1/master/gpseg-1/pg_hba.conf &&
        gpconfig -c gp_resource_manager -v group &&
        gpstop -aM fast && gpstart -a"
  else
    echo "-------------------------"
    echo "Starting Greenplum server"
    echo "-------------------------"
    sudo -H -u gpadmin bash -c "source /home/gpadmin/.bash_profile && gpstart -a"
    result="$( sudo -H -u gpadmin bash -c "source /home/gpadmin/.bash_profile && /usr/local/greenplum-db-devel/bin/psql -d postgres -Atc 'SELECT 1;'" )"
    if [ "${result}" == "1" ]; then
      echo "--------------------------------------------"
      echo "Fantastic!!! Greenplum cluster is available!"
      echo "--------------------------------------------"
    else
      echo "-------------------------------------"
      echo "Error to connect to Greenplum cluster"
      echo "-------------------------------------"
      exit 1;
    fi
  fi
fi

echo "------------------------------------"
echo "Prepare configs for automation tests"
echo "------------------------------------"
sudo -H -u gpadmin bash -c -l "HIVE_SERVER_HOST=$HIVE_SERVER_HOST JDBC_HOST=$DOCKER_GP_MASTER_SERVER make -C ~/workspace/pxf/automation  sync_jdbc_config"

# Start PXF
for server in $DOCKER_GP_CLUSTER_HOSTS
do
  echo "---------"
  echo "Start PXF"
  echo "---------"
  # Init Vault environment
  if [[ "$PXF_VAULT_ENABLED" = true ]]; then
      echo "----------------------------------------"
      echo "Init Vault env variables for PXF service"
      echo "----------------------------------------"
      ksh -c env | grep -E 'PXF_VAULT' | sed 's/^/export /' >> /home/gpadmin/.bash_profile
      ksh -c env | grep -E 'PXF_VAULT' | sed 's/^/export /' >> /home/gpadmin/.bashrc
  fi
  # Init SSL environment
  if [[ "$PXF_PROTOCOL" = "https" ]]; then
    echo "--------------------------------------"
    echo "Init SSL env variables for PXF service"
    echo "--------------------------------------"
    ksh -c env | grep -E 'PXF_SSL|PXF_HOST|PXF_PROTOCOL' | sed 's/^/export /' >> /home/gpadmin/.bash_profile
    ksh -c env | grep -E 'PXF_SSL|PXF_HOST|PXF_PROTOCOL' | sed 's/^/export /' >> /home/gpadmin/.bashrc
  fi

  if [ "$HOSTNAME" == "$DOCKER_GP_MASTER_SERVER" ]; then
    sudo -H -u gpadmin bash -c -l "pxf start && tail -f /data1/master/gpseg-1/pg_log/gpdb-*.csv"
  else
    sudo -H -u gpadmin bash -c -l "pxf start && tail -f /usr/local/greenplum-db-devel/pxf/logs/pxf-service.log"
  fi
done
