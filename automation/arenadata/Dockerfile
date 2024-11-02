ARG GPDB_IMAGE=cloud-hub.adsw.io/library/gpdb6_u22:adb-6.x-dev
FROM $GPDB_IMAGE

SHELL ["bash", "-c"]

# install maven
RUN curl -fSL https://dlcdn.apache.org/maven/maven-3/3.9.6/binaries/apache-maven-3.9.6-bin.tar.gz -o /tmp/apache-maven-3.9.6-bin.tar.gz \
    && tar -xf /tmp/apache-maven-3.9.6-bin.tar.gz -C /opt \
    && ln -s /opt/apache-maven-3.9.6 /opt/maven

# Download jar files
RUN mkdir -p /tmp/libs
RUN curl -fSL https://repo1.maven.org/maven2/io/arenadata/security/encryption/1.0.0/encryption-1.0.0-exec.jar -o /tmp/libs/encryption-1.0.0-exec.jar
RUN curl -fSL https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/12.2.0.1/ojdbc8-12.2.0.1.jar -o /tmp/libs/ojdbc8-12.2.0.1.jar
RUN curl -fSL https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar -o /tmp/libs/mysql-connector-j-8.3.0.jar

# install go, ginkgo and keep env variables which may be used as a part of base image
RUN set -eux; \
    ARCH="$(uname -m)"; \
    case "${ARCH}" in \
       aarch64|armhf|armv7l|amd64|x86_64) \
         curl -fSL https://go.dev/dl/go1.21.3.linux-amd64.tar.gz -o go1.21.3.linux-amd64.tar.gz \
         && rm -rf /usr/local/go && tar -C /usr/local -xzf go1.21.3.linux-amd64.tar.gz && rm go1.21.3.linux-amd64.tar.gz \
         ;; \
       arm64) \
         curl -fSL https://go.dev/dl/go1.21.3.linux-arm64.tar.gz -o go1.21.3.linux-arm64.tar.gz \
         && rm -rf /usr/local/go && tar -C /usr/local -xzf go1.21.3.linux-arm64.tar.gz && rm go1.21.3.linux-arm64.tar.gz \
         ;; \
       ppc64el|ppc64le) \
         curl -fSL https://go.dev/dl/go1.21.3.linux-ppc64le.tar.gz -o go1.21.3.linux-ppc64le.tar.gz \
         && rm -rf /usr/local/go && tar -C /usr/local -xzf go1.21.3.linux-ppc64le.tar.gz && rm go1.21.3.linux-ppc64le.tar.gz \
         ;; \
       *) \
         echo "Unsupported arch: ${ARCH}"; \
         exit 1; \
         ;; \
    esac; \
    . /etc/os-release; \
    case "$ID" in \
       centos*) \
         yum-config-manager --disable epel && yum-config-manager --add-repo 'http://archives.fedoraproject.org/pub/archive/epel/7/$basearch'; \
         curl -fSL https://download.oracle.com/java/17/archive/jdk-17.0.12_linux-x64_bin.rpm -o /tmp/jdk-17.0.12_linux-x64_bin.rpm && \
           yum -y install /tmp/jdk-17.0.12_linux-x64_bin.rpm && \
           rm -rf /tmp/jdk-17.0.12_linux-x64_bin.rpm; \
         sed -i "s/JAVA_HOME=.*/JAVA_HOME=\$(readlink -f \/usr\/bin\/java | sed 's:bin\/java::')/g" /etc/profile.d/jdk_home.sh; \
         ;; \
       ubuntu*) \
         apt-get -y update && apt-get install -y unzip vim nano openjdk-17-jdk; \
         update-locale LANG=en_US.UTF-8; \
         ;; \
    esac;
ENV GOPATH=$HOME/go
ENV PATH=$PATH:/usr/local/go/bin:$GOPATH/bin
RUN go install github.com/onsi/ginkgo/ginkgo@latest

# Install gpdb source files
WORKDIR /home/gpadmin/
RUN mkdir -p /data1/master /data1/primary /data1/mirror && chmod -R 755 /data1 && \
    gpdb_src/concourse/scripts/setup_gpadmin_user.bash && \
    source gpdb_src/concourse/scripts/common.bash  && \
    chown -R gpadmin:gpadmin /data1 && \
    chown -R gpadmin:gpadmin /home/gpadmin && \
    install_gpdb

# Install PXF
ENV JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF8
COPY . /home/gpadmin/pxf_src
COPY ./automation/arenadata/scripts/compile_pxf_without_test.sh ./pxf_src/concourse/scripts/compile_pxf_without_test.sh
RUN chmod +x ./pxf_src/concourse/scripts/compile_pxf_without_test.sh
RUN source gpdb_src/concourse/scripts/common.bash && \
    mkdir pxf_tarball && \
    install_gpdb && \
    source '/usr/local/greenplum-db-devel/greenplum_path.sh' && \
    export SKIP_FDW_BUILD_REASON=0 && \
    pxf_src/concourse/scripts/compile_pxf_without_test.sh && \
    chown -R gpadmin:gpadmin /home/gpadmin/pxf_src

RUN mkdir workspace && ln -s /home/gpadmin/pxf_src /home/gpadmin/workspace/pxf && chown -R gpadmin:gpadmin /home/gpadmin/workspace

# Need to run automation tests
ENV PXF_HOME=/usr/local/greenplum-db-devel/pxf
RUN localedef -c -i ru_RU -f CP1251 ru_RU.CP1251
RUN cp ${PXF_HOME}/templates/*-site.xml ${PXF_HOME}/servers/default/

# Need to change ssh key to RSA for automation tests with Ubuntu
RUN set -eux; \
    . /etc/os-release; \
        case "$ID" in \
           ubuntu*) \
             ssh-keygen -p -P "" -N "" -m pem -f /home/gpadmin/.ssh/id_rsa; \
             ssh-keygen -p -P "" -N "" -m pem -f /root/.ssh/id_rsa; \
             echo "KexAlgorithms +diffie-hellman-group-exchange-sha1" >> /etc/ssh/sshd_config; \
             echo "PubkeyAcceptedAlgorithms +ssh-rsa" >> /etc/ssh/sshd_config; \
             echo "HostKeyAlgorithms +ssh-rsa" >> /etc/ssh/sshd_config; \
             ;; \
        esac;

# Move libs to the destination folder
RUN cp /tmp/libs/* ${PXF_HOME}/lib/

RUN chmod a+x ./pxf_src/automation/arenadata/scripts/start_adb_cluster.sh
RUN chown -R gpadmin:gpadmin /usr/local/greenplum-db-devel
ENTRYPOINT ["/home/gpadmin/pxf_src/automation/arenadata/scripts/start_adb_cluster.sh"]
