package cn.iocoder.mall.admin.service;

import cn.iocoder.common.framework.constant.CommonStatusEnum;
import cn.iocoder.common.framework.constant.DeletedStatusEnum;
import cn.iocoder.common.framework.util.CollectionUtil;
import cn.iocoder.common.framework.util.ServiceExceptionUtil;
import cn.iocoder.common.framework.vo.CommonResult;
import cn.iocoder.common.framework.vo.PageResult;
import cn.iocoder.mall.admin.api.AdminService;
import cn.iocoder.mall.admin.api.bo.role.RoleBO;
import cn.iocoder.mall.admin.api.bo.admin.AdminBO;
import cn.iocoder.mall.admin.api.constant.AdminConstants;
import cn.iocoder.mall.admin.api.constant.AdminErrorCodeEnum;
import cn.iocoder.mall.admin.api.dto.admin.*;
import cn.iocoder.mall.admin.convert.AdminConvert;
import cn.iocoder.mall.admin.dao.AdminMapper;
import cn.iocoder.mall.admin.dao.AdminRoleMapper;
import cn.iocoder.mall.admin.dataobject.AdminDO;
import cn.iocoder.mall.admin.dataobject.AdminRoleDO;
import cn.iocoder.mall.admin.dataobject.RoleDO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@org.apache.dubbo.config.annotation.Service(validation = "true", version = "${dubbo.provider.AdminService.version}")
public class AdminServiceImpl implements AdminService {

    @Autowired
    private AdminMapper adminMapper;
    @Autowired
    private AdminRoleMapper adminRoleMapper;

    @Autowired
    private OAuth2ServiceImpl oAuth2Service;
    @Autowired
    private RoleServiceImpl roleService;

    public CommonResult<AdminDO> validAdmin(String username, String password) {
        AdminDO admin = adminMapper.selectByUsername(username);
        // 账号不存在
        if (admin == null) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 密码不正确
        if (encodePassword(password).equals(admin.getPassword())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_PASSWORD_ERROR.getCode());
        }
        // 账号被禁用
        if (CommonStatusEnum.DISABLE.getValue().equals(admin.getStatus())) {
            return ServiceExceptionUtil.error(AdminErrorCodeEnum.ADMIN_IS_DISABLE.getCode());
        }
        // 校验成功，返回管理员。并且，去掉一些非关键字段，考虑安全性。
        admin.setPassword(null);
        admin.setStatus(null);
        return CommonResult.success(admin);
    }

    public List<AdminRoleDO> getAdminRoles(Integer adminId) {
        return adminRoleMapper.selectByAdminId(adminId);
    }

    @Override
    public PageResult<AdminBO> getAdminPage(AdminPageDTO adminPageDTO) {
        IPage<AdminDO> page = adminMapper.selectPage(adminPageDTO);
        return AdminConvert.INSTANCE.convert(page);
    }

    @Override
    public AdminBO addAdmin(Integer adminId, AdminAddDTO adminAddDTO) {
        // 校验账号唯一
        if (adminMapper.selectByUsername(adminAddDTO.getUsername()) != null) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_USERNAME_EXISTS.getCode());
        }
        // 保存到数据库
        AdminDO admin = AdminConvert.INSTANCE.convert(adminAddDTO)
                .setPassword(encodePassword(adminAddDTO.getPassword())) // 加密密码
                .setStatus(CommonStatusEnum.ENABLE.getValue());
        admin.setCreateTime(new Date());
        admin.setDeleted(DeletedStatusEnum.DELETED_NO.getValue());
        adminMapper.insert(admin);
        // TODO 插入操作日志
        // 返回成功
        return AdminConvert.INSTANCE.convert(admin);
    }

    @Override
    public Boolean updateAdmin(Integer adminId, AdminUpdateDTO adminUpdateDTO) {
        // 校验账号存在
        if (adminMapper.selectById(adminUpdateDTO.getId()) == null) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 校验账号唯一
        AdminDO usernameAdmin = adminMapper.selectByUsername(adminUpdateDTO.getUsername());
        if (usernameAdmin != null && !usernameAdmin.getId().equals(adminUpdateDTO.getId())) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_USERNAME_EXISTS.getCode());
        }
        // 更新到数据库
        AdminDO updateAdmin = AdminConvert.INSTANCE.convert(adminUpdateDTO);
        adminMapper.updateById(updateAdmin);
        // TODO 插入操作日志
        // 返回成功
        return true;
    }

    @Override
    @Transactional
    public Boolean updateAdminStatus(Integer adminId, AdminUpdateStatusDTO adminUpdateStatusDTO) {
        // 校验账号存在
        AdminDO admin = adminMapper.selectById(adminUpdateStatusDTO.getId());
        if (admin == null) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        if (AdminConstants.USERNAME_ADMIN.equals(admin.getUsername())) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_ADMIN_STATUS_CAN_NOT_UPDATE.getCode());
        }
        // 如果状态相同，则返回错误
        if (adminUpdateStatusDTO.getStatus().equals(admin.getStatus())) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_STATUS_EQUALS.getCode());
        }
        // 更新管理员状态
        AdminDO updateAdmin = new AdminDO().setId(adminUpdateStatusDTO.getId()).setStatus(adminUpdateStatusDTO.getStatus());
        adminMapper.updateById(updateAdmin);
        // 如果是关闭管理员，则标记 token 失效。否则，管理员还可以继续蹦跶
        if (CommonStatusEnum.DISABLE.getValue().equals(adminUpdateStatusDTO.getStatus())) {
            oAuth2Service.removeToken(adminUpdateStatusDTO.getId());
        }
        // TODO 插入操作日志
        // 返回成功
        return true;
    }

    @Override
    @Transactional
    public Boolean deleteAdmin(Integer adminId, Integer updateAdminId) {
        // 校验账号存在
        AdminDO admin = adminMapper.selectById(updateAdminId);
        if (admin == null) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 只有禁用的账号才可以删除
        if (CommonStatusEnum.ENABLE.getValue().equals(admin.getStatus())) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_DELETE_ONLY_DISABLE.getCode());
        }
        // 标记删除 AdminDO
        adminMapper.deleteById(updateAdminId); // 标记删除
        // 标记删除 AdminRole
        adminRoleMapper.updateToDeletedByAdminId(updateAdminId);
        // TODO 插入操作日志
        // 返回成功
        return true;
    }

    @Override
    public Map<Integer, Collection<RoleBO>> getAdminRolesMap(Collection<Integer> adminIds) {
        // 查询管理员拥有的角色关联数据
        List<AdminRoleDO> adminRoleList = adminRoleMapper.selectListByAdminIds(adminIds);
        if (adminRoleList.isEmpty()) {
            return Collections.emptyMap();
        }
        // 查询角色数据
        List<RoleBO> roleList = roleService.getRoleList(CollectionUtil.convertSet(adminRoleList, AdminRoleDO::getRoleId));
        Map<Integer, RoleBO> roleMap = CollectionUtil.convertMap(roleList, RoleBO::getId);
        // 拼接数据
        Multimap<Integer, RoleBO> result = ArrayListMultimap.create();
        adminRoleList.forEach(adminRole -> result.put(adminRole.getAdminId(), roleMap.get(adminRole.getRoleId())));
        return result.asMap();
    }

    @Override
    public List<RoleBO> getRoleList(Integer adminId) {
        // 查询管理员拥有的角色关联数据
        List<AdminRoleDO> adminRoleList = adminRoleMapper.selectByAdminId(adminId);
        if (adminRoleList.isEmpty()) {
            return Collections.emptyList();
        }
        // 查询角色数据
        return roleService.getRoleList(CollectionUtil.convertSet(adminRoleList, AdminRoleDO::getRoleId));
    }

    @Override
    @Transactional
    public Boolean assignAdminRole(Integer adminId, AdminAssignRoleDTO adminAssignRoleDTO) {
        // 校验账号存在
        AdminDO admin = adminMapper.selectById(adminAssignRoleDTO.getId());
        if (admin == null) {
            throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_USERNAME_NOT_REGISTERED.getCode());
        }
        // 校验是否有不存在的角色
        if (!CollectionUtil.isEmpty(adminAssignRoleDTO.getRoleIds())) {
            List<RoleDO> roles = roleService.getRoles(adminAssignRoleDTO.getRoleIds());
            if (roles.size() != adminAssignRoleDTO.getRoleIds().size()) {
                throw ServiceExceptionUtil.exception(AdminErrorCodeEnum.ADMIN_ASSIGN_ROLE_NOT_EXISTS.getCode());
            }
        }
        // TODO 芋艿，这里先简单实现。即方式是，删除老的分配的角色关系，然后添加新的分配的角色关系
        // 标记管理员角色源关系都为删除
        adminRoleMapper.updateToDeletedByAdminId(adminAssignRoleDTO.getId());
        // 创建 RoleResourceDO 数组，并插入到数据库
        if (!CollectionUtil.isEmpty(adminAssignRoleDTO.getRoleIds())) {
            List<AdminRoleDO> adminRoleDOs = adminAssignRoleDTO.getRoleIds().stream().map(roleId -> {
                AdminRoleDO roleResource = new AdminRoleDO().setAdminId(adminAssignRoleDTO.getId()).setRoleId(roleId);
                roleResource.setCreateTime(new Date());
                roleResource.setDeleted(DeletedStatusEnum.DELETED_NO.getValue());
                return roleResource;
            }).collect(Collectors.toList());
            adminRoleMapper.insertList(adminRoleDOs);
        }
        // TODO 插入操作日志
        // 返回成功
        return true;
    }

    private String encodePassword(String password) {
        return DigestUtils.md5DigestAsHex(password.getBytes());
    }

}
