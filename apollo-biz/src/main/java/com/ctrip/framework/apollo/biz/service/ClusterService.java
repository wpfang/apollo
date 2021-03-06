package com.ctrip.framework.apollo.biz.service;

import com.ctrip.framework.apollo.biz.entity.Audit;
import com.ctrip.framework.apollo.biz.entity.Cluster;
import com.ctrip.framework.apollo.biz.repository.ClusterRepository;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.exception.ServiceException;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.ctrip.framework.apollo.core.ConfigConsts;
import com.google.common.base.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class ClusterService {

    @Autowired
    private ClusterRepository clusterRepository;
    @Autowired
    private AuditService auditService;
    @Autowired
    private NamespaceService namespaceService;

    public boolean isClusterNameUnique(String appId, String clusterName) {
        Objects.requireNonNull(appId, "AppId must not be null");
        Objects.requireNonNull(clusterName, "ClusterName must not be null");
        return Objects.isNull(clusterRepository.findByAppIdAndName(appId, clusterName));
    }

    public Cluster findOne(String appId, String name) {
        return clusterRepository.findByAppIdAndName(appId, name);
    }

    public Cluster findOne(long clusterId) {
        return clusterRepository.findOne(clusterId);
    }

    public List<Cluster> findParentClusters(String appId) {
        if (Strings.isNullOrEmpty(appId)) {
            return Collections.emptyList();
        }

        List<Cluster> clusters = clusterRepository.findByAppIdAndParentClusterId(appId, 0L);
        if (clusters == null) {
            return Collections.emptyList();
        }

        Collections.sort(clusters);

        return clusters;
    }

    @Transactional
    public Cluster saveWithInstanceOfAppNamespaces(Cluster entity) {
        // 保存 Cluster 对象
        Cluster savedCluster = saveWithoutInstanceOfAppNamespaces(entity);
        // 并创建 Cluster 的 Namespace 们
        namespaceService.instanceOfAppNamespaces(savedCluster.getAppId(), savedCluster.getName(), savedCluster.getDataChangeCreatedBy());
        return savedCluster;
    }

    @Transactional
    public Cluster saveWithoutInstanceOfAppNamespaces(Cluster entity) {
        // 判断 `name` 在 App 下是否已经存在对应的 Cluster 对象。若已经存在，抛出 BadRequestException 异常。
        if (!isClusterNameUnique(entity.getAppId(), entity.getName())) {
            throw new BadRequestException("cluster not unique");
        }
        // 保存 Cluster 对象到数据库
        entity.setId(0);//protection
        Cluster cluster = clusterRepository.save(entity);
        // 【TODO 6001】Tracer 日志
        auditService.audit(Cluster.class.getSimpleName(), cluster.getId(), Audit.OP.INSERT, cluster.getDataChangeCreatedBy());
        return cluster;
    }

    // 删除 Cluster
    @Transactional
    public void delete(long id, String operator) {
        // 获得 Cluster 对象
        Cluster cluster = clusterRepository.findOne(id);
        if (cluster == null) {
            throw new BadRequestException("cluster not exist");
        }
        // 删除 Namespace
        // delete linked namespaces
        namespaceService.deleteByAppIdAndClusterName(cluster.getAppId(), cluster.getName(), operator);

        // 标记删除 Cluster
        cluster.setDeleted(true);
        cluster.setDataChangeLastModifiedBy(operator);
        clusterRepository.save(cluster);

        // 【TODO 6002】Audit
        auditService.audit(Cluster.class.getSimpleName(), id, Audit.OP.DELETE, operator);
    }

    @Transactional
    public Cluster update(Cluster cluster) {
        Cluster managedCluster =
                clusterRepository.findByAppIdAndName(cluster.getAppId(), cluster.getName());
        BeanUtils.copyEntityProperties(cluster, managedCluster);
        managedCluster = clusterRepository.save(managedCluster);

        auditService.audit(Cluster.class.getSimpleName(), managedCluster.getId(), Audit.OP.UPDATE,
                managedCluster.getDataChangeLastModifiedBy());

        return managedCluster;
    }

    @Transactional
    public void createDefaultCluster(String appId, String createBy) {
        if (!isClusterNameUnique(appId, ConfigConsts.CLUSTER_NAME_DEFAULT)) {
            throw new ServiceException("cluster not unique");
        }
        Cluster cluster = new Cluster();
        cluster.setName(ConfigConsts.CLUSTER_NAME_DEFAULT);
        cluster.setAppId(appId);
        cluster.setDataChangeCreatedBy(createBy);
        cluster.setDataChangeLastModifiedBy(createBy);
        clusterRepository.save(cluster);

        auditService.audit(Cluster.class.getSimpleName(), cluster.getId(), Audit.OP.INSERT, createBy);
    }

    /**
     * 获得子 Cluster 数组
     *
     * @param appId App 编号
     * @param parentClusterName Cluster 名字
     * @return 子 Cluster 数组
     */
    public List<Cluster> findChildClusters(String appId, String parentClusterName) {
        // 获得父 Cluster 对象
        Cluster parentCluster = findOne(appId, parentClusterName);
        // 若不存在，抛出 BadRequestException 异常
        if (parentCluster == null) {
            throw new BadRequestException("parent cluster not exist");
        }
        // 获得子 Cluster 数组
        return clusterRepository.findByParentClusterId(parentCluster.getId());
    }

}
