package com.ctrip.framework.apollo.biz.service;

import com.ctrip.framework.apollo.biz.entity.*;
import com.ctrip.framework.apollo.biz.repository.GrayReleaseRuleRepository;
import com.ctrip.framework.apollo.common.constants.NamespaceBranchStatus;
import com.ctrip.framework.apollo.common.constants.ReleaseOperation;
import com.ctrip.framework.apollo.common.constants.ReleaseOperationContext;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.utils.GrayReleaseRuleItemTransformer;
import com.ctrip.framework.apollo.common.utils.UniqueKeyGenerator;
import com.google.common.collect.Maps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Namespace 分支 Service
 */
@Service
public class NamespaceBranchService {

    @Autowired
    private AuditService auditService;
    @Autowired
    private GrayReleaseRuleRepository grayReleaseRuleRepository;
    @Autowired
    private ClusterService clusterService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private NamespaceService namespaceService;
    @Autowired
    private ReleaseHistoryService releaseHistoryService;

    @Transactional
    public Namespace createBranch(String appId, String parentClusterName, String namespaceName, String operator) {
        // 获得子 Namespace 对象
        Namespace childNamespace = findBranch(appId, parentClusterName, namespaceName);
        // 若存在子 Namespace 对象，则抛出 BadRequestException 异常。一个 Namespace 有且仅允许有一个子 Namespace 。
        if (childNamespace != null) {
            throw new BadRequestException("namespace already has branch");
        }
        // 获得父 Cluster 对象
        Cluster parentCluster = clusterService.findOne(appId, parentClusterName);
        // 若父 Cluster 对象不存在，抛出 BadRequestException 异常
        if (parentCluster == null || parentCluster.getParentClusterId() != 0) {
            throw new BadRequestException("cluster not exist or illegal cluster");
        }

        // 创建子 Cluster 对象
        // create child cluster
        Cluster childCluster = createChildCluster(appId, parentCluster, namespaceName, operator);
        // 保存子 Cluster 对象
        Cluster createdChildCluster = clusterService.saveWithoutInstanceOfAppNamespaces(childCluster);

        // 创建子 Namespace 对象
        // create child namespace
        childNamespace = createNamespaceBranch(appId, createdChildCluster.getName(), namespaceName, operator);
        // 保存子 Namespace 对象
        return namespaceService.save(childNamespace);
    }

    public Namespace findBranch(String appId, String parentClusterName, String namespaceName) {
        return namespaceService.findChildNamespace(appId, parentClusterName, namespaceName);
    }

    public GrayReleaseRule findBranchGrayRules(String appId, String clusterName, String namespaceName,
                                               String branchName) {
        return grayReleaseRuleRepository.findTopByAppIdAndClusterNameAndNamespaceNameAndBranchNameOrderByIdDesc(appId, clusterName, namespaceName, branchName);
    }

    // 更新子 Namespace 的灰度发布规则
    @Transactional
    public void updateBranchGrayRules(String appId, String clusterName, String namespaceName, String branchName, GrayReleaseRule newRules) {
        doUpdateBranchGrayRules(appId, clusterName, namespaceName, branchName, newRules, true, ReleaseOperation.APPLY_GRAY_RULES);
    }

    private void doUpdateBranchGrayRules(String appId, String clusterName, String namespaceName,
                                         String branchName, GrayReleaseRule newRules, boolean recordReleaseHistory, int releaseOperation) {
        // 获得子 Namespace 的灰度发布规则
        GrayReleaseRule oldRules = grayReleaseRuleRepository.findTopByAppIdAndClusterNameAndNamespaceNameAndBranchNameOrderByIdDesc(appId, clusterName, namespaceName, branchName);
        // 获得最新的子 Namespace 的 Release 对象
        Release latestBranchRelease = releaseService.findLatestActiveRelease(appId, branchName, namespaceName);
        // 获得最新的子 Namespace 的 Release 对象的编号
        long latestBranchReleaseId = latestBranchRelease != null ? latestBranchRelease.getId() : 0;
        // 设置 GrayReleaseRule 的 `releaseId`
        newRules.setReleaseId(latestBranchReleaseId);
        // 保存新的 GrayReleaseRule 对象
        grayReleaseRuleRepository.save(newRules);

        // 删除老的 GrayReleaseRule 对象
        // delete old rules
        if (oldRules != null) {
            grayReleaseRuleRepository.delete(oldRules);
        }

        // 若需要，创建 ReleaseHistory 对象，并保存
        if (recordReleaseHistory) {
            Map<String, Object> releaseOperationContext = Maps.newHashMap();
            releaseOperationContext.put(ReleaseOperationContext.RULES, GrayReleaseRuleItemTransformer.batchTransformFromJSON(newRules.getRules())); // 新规则
            if (oldRules != null) {
                releaseOperationContext.put(ReleaseOperationContext.OLD_RULES, GrayReleaseRuleItemTransformer.batchTransformFromJSON(oldRules.getRules())); // 老规则
            }
            releaseHistoryService.createReleaseHistory(appId, clusterName, namespaceName, branchName, latestBranchReleaseId,
                    latestBranchReleaseId, releaseOperation, releaseOperationContext, newRules.getDataChangeLastModifiedBy());
        }
    }

    @Transactional
    public GrayReleaseRule updateRulesReleaseId(String appId, String clusterName, String namespaceName, String branchName, long latestReleaseId, String operator) {
        // 获得老的 GrayReleaseRule 对象
        GrayReleaseRule oldRules = grayReleaseRuleRepository.findTopByAppIdAndClusterNameAndNamespaceNameAndBranchNameOrderByIdDesc(appId, clusterName, namespaceName, branchName);
        if (oldRules == null) {
            return null;
        }

        // 创建新的 GrayReleaseRule 对象
        GrayReleaseRule newRules = new GrayReleaseRule();
        newRules.setBranchStatus(NamespaceBranchStatus.ACTIVE);
        newRules.setReleaseId(latestReleaseId); // update
        newRules.setRules(oldRules.getRules());
        newRules.setAppId(oldRules.getAppId());
        newRules.setClusterName(oldRules.getClusterName());
        newRules.setNamespaceName(oldRules.getNamespaceName());
        newRules.setBranchName(oldRules.getBranchName());
        newRules.setDataChangeCreatedBy(operator); // update
        newRules.setDataChangeLastModifiedBy(operator); // update

        // 保存新的 GrayReleaseRule 对象
        grayReleaseRuleRepository.save(newRules);
        // 删除老的 GrayReleaseRule 对象
        grayReleaseRuleRepository.delete(oldRules);
        return newRules;
    }

    @Transactional
    public void deleteBranch(String appId, String clusterName, String namespaceName,
                             String branchName, int branchStatus, String operator) {
        // 获得子 Cluster 对象
        Cluster toDeleteCluster = clusterService.findOne(appId, branchName);
        if (toDeleteCluster == null) {
            return;
        }
        // 获得子 Namespace 的最后有效的 Release 对象
        Release latestBranchRelease = releaseService.findLatestActiveRelease(appId, branchName, namespaceName);
        // 获得子 Namespace 的最后有效的 Release 对象的编号
        long latestBranchReleaseId = latestBranchRelease != null ? latestBranchRelease.getId() : 0;

        // 创建新的，用于表示删除的 GrayReleaseRule 的对象
        // update branch rules
        GrayReleaseRule deleteRule = new GrayReleaseRule();
        deleteRule.setRules("[]");
        deleteRule.setAppId(appId);
        deleteRule.setClusterName(clusterName);
        deleteRule.setNamespaceName(namespaceName);
        deleteRule.setBranchName(branchName);
        deleteRule.setBranchStatus(branchStatus); // Namespace 分支状态
        deleteRule.setDataChangeLastModifiedBy(operator);
        deleteRule.setDataChangeCreatedBy(operator);
        // 更新 GrayReleaseRule
        doUpdateBranchGrayRules(appId, clusterName, namespaceName, branchName, deleteRule, false, -1);

        // 删除子 Cluster
        // delete branch cluster
        clusterService.delete(toDeleteCluster.getId(), operator);

        // 创建 ReleaseHistory 对象，并保存
        int releaseOperation = branchStatus == NamespaceBranchStatus.MERGED ? ReleaseOperation.GRAY_RELEASE_DELETED_AFTER_MERGE : ReleaseOperation.ABANDON_GRAY_RELEASE;
        releaseHistoryService.createReleaseHistory(appId, clusterName, namespaceName, branchName, latestBranchReleaseId, latestBranchReleaseId,
                releaseOperation, null, operator);
        // 【TODO 6002】Audit
        auditService.audit("Branch", toDeleteCluster.getId(), Audit.OP.DELETE, operator);
    }

    private Cluster createChildCluster(String appId, Cluster parentCluster,
                                       String namespaceName, String operator) {
        Cluster childCluster = new Cluster();
        childCluster.setAppId(appId);
        childCluster.setParentClusterId(parentCluster.getId());
        childCluster.setName(UniqueKeyGenerator.generate(appId, parentCluster.getName(), namespaceName));
        childCluster.setDataChangeCreatedBy(operator);
        childCluster.setDataChangeLastModifiedBy(operator);
        return childCluster;
    }

    private Namespace createNamespaceBranch(String appId, String clusterName, String namespaceName, String operator) {
        Namespace childNamespace = new Namespace();
        childNamespace.setAppId(appId);
        childNamespace.setClusterName(clusterName);
        childNamespace.setNamespaceName(namespaceName);
        childNamespace.setDataChangeLastModifiedBy(operator);
        childNamespace.setDataChangeCreatedBy(operator);
        return childNamespace;
    }

}
