#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/device.h>
#include <linux/power_supply.h>
#include <linux/kprobes.h>
#include <linux/uaccess.h>
#include <linux/mutex.h>
#include <linux/fs.h>
#include <linux/file.h>
#include <linux/slab.h>
#include <linux/timer.h>
#include <linux/notifier.h>
#include <linux/workqueue.h>
#include <linux/kmod.h>
#include <linux/notifier.h>
#include <linux/workqueue.h>

/* 允许通过内核态写 pd_verifed（使用 VFS 内部 API） */
#define DISABLE_PD_VERIFED 1

/*
 * chg_param_override: 通过 kretprobe 在 power_supply 层覆盖/注入可写参数，
 * 结合用户态（LSPosed Hook 应用）经 procfs 接口写入期望的充电参数，实现：
 * - 目标电压 voltage_max (uV)
 * - 恒流/终止电流 constant_charge_current / charge_termination_current (uA) [若驱动支持]
 * - USB 输入电流限制 input_current_limit (uA)
 * - 充电速率/限速：通过限制 input_current_limit 或调整 constant_charge_current 实现
 * - PD 协议切换：控制 pd_verifed 在 PPS (1) 和 MIPPS (0) 间切换
 *
 * 本模块采用两种路径：
 * 1) 直接写 sysfs（若节点可写且 SELinux 允许）
 * 2) 在 power_supply_show_property/get_property 返回时覆盖显示值，
 *    并在 set_property 路径通过 kprobe/kretprobe 劫持（若目标符号可见）
 *
 * 为兼容性，本实现先提供一个简洁的 proc 接口：/proc/chg_param_override
 * 用户可写入 JSON 风格的简单键值：
 *   {"voltage_max": 4460000, "constant_charge_current": 6000000, "input_current_limit": 1500000, "pd_verifed": 1}
 * 或使用简写行： key=value 换行分隔
 */
 

static char target_batt[32] = "battery";
module_param_string(target_batt, target_batt, sizeof(target_batt), 0644);
MODULE_PARM_DESC(target_batt, "power_supply name for battery (default: battery)");

static char target_usb[16] = "usb";
module_param_string(target_usb, target_usb, sizeof(target_usb), 0644);
MODULE_PARM_DESC(target_usb, "power_supply name for usb (default: usb)");

static bool verbose = true;
module_param(verbose, bool, 0644);

static bool auto_reapply = true;
module_param(auto_reapply, bool, 0644);
MODULE_PARM_DESC(auto_reapply, "Auto reapply pd_verifed setting after cable replug");

// PD Verified 路径
#if !DISABLE_PD_VERIFED
static char pd_verifed_path[128] = "/sys/class/qcom-battery/pd_verifed";
module_param_string(pd_verifed_path, pd_verifed_path, sizeof(pd_verifed_path), 0644);
MODULE_PARM_DESC(pd_verifed_path, "Path to pd_verifed sysfs node");
#endif

struct chg_targets {
    /* 原有充电参数 - 单位 uV / uA 按内核约定 */
    int voltage_max_uv;                /* 电池目标电压 */
    int constant_charge_current_ua;    /* 电池恒流（近似充电电流上限） */
    int term_current_ua;               /* 终止电流（若驱动支持 set_property） */
    int usb_input_current_limit_ua;    /* USB 输入电流限制 */
    
    /* 新增：电池充电控制 */
    int charge_control_limit_percent;  /* 充电限制百分比 (0-100) */
    
    /* 新增：PD 协议控制（可选，默认禁用以兼容 GKI） */
    int pd_verifed;                    /* PD Verified: 0=MIPPS, 1=PPS */
    bool pd_verifed_enabled;           /* 是否启用 pd_verifed 控制 */
    int last_pd_verifed;               /* 上次读取的 pd_verifed 值 */
};

static struct chg_targets g_targets;
static DEFINE_MUTEX(g_lock);

/* 事件驱动自动重写：power_supply 通知 + 延迟工作合并写入 */
static struct notifier_block psy_nb;
static struct delayed_work reapply_work;

/* 前向声明，供工作队列回调调用 */
static int apply_targets_locked(void);

static void reapply_work_fn(struct work_struct *work)
{
    mutex_lock(&g_lock);
    (void)apply_targets_locked();
    mutex_unlock(&g_lock);
}

static int psy_event_handler(struct notifier_block *nb, unsigned long event, void *data)
{
    struct power_supply *psy = data;
    const char *name;

    if (event != PSY_EVENT_PROP_CHANGED || !psy || !psy->desc)
        return NOTIFY_DONE;

    name = psy->desc->name;
    if (!name)
        return NOTIFY_DONE;

    /* 仅对我们关心的电源触发，合并频繁事件避免抖动 */
    if (!strcmp(name, target_batt) || !strcmp(name, target_usb)) {
        schedule_delayed_work(&reapply_work, msecs_to_jiffies(200));
        return NOTIFY_OK;
    }
    return NOTIFY_DONE;
}

#if !DISABLE_PD_VERIFED
/* 通过用户态助手写 sysfs，避免依赖 VFS 内部符号 */
static int umh_write_sysfs_int(const char *path, int value)
{
    char cmd[256];
    char *argv[] = { "/system/bin/sh", "-c", cmd, NULL };
    /* 追加常见 PATH，确保 echo/tee 可用 */
    char *envp[] = {
        "HOME=/",
        "TERM=linux",
        "PATH=/system/bin:/system/xbin:/system/vendor/bin:/vendor/bin:/odm/bin",
        NULL,
    };
    int rc;
    scnprintf(cmd, sizeof(cmd), "echo %d > %s", value, path);
    rc = call_usermodehelper(argv[0], argv, envp, UMH_WAIT_PROC);
    return rc;
}
#endif

/* PD Verified 控制函数 */
#if !DISABLE_PD_VERIFED
static int set_pd_verifed(int value)
{
    int ret;
    if (value != 0 && value != 1)
        return -EINVAL;
    ret = umh_write_sysfs_int(pd_verifed_path, value);
    if (ret == 0)
        g_targets.last_pd_verifed = value;
    return ret;
}
#endif

#if !DISABLE_PD_VERIFED
static int get_pd_verifed(void)
{
    /* 当前不读取，返回不支持，避免依赖 VFS/捕获输出 */
    return -EOPNOTSUPP;
}
#endif

static struct power_supply *find_psy_by_name(const char *name)
{
    return power_supply_get_by_name(name);
}

static int write_psy_int(struct power_supply *psy, enum power_supply_property psp, int val)
{
    int ret;
    union power_supply_propval prop = {0};
    struct power_supply_desc *desc;
    if (!psy)
        return -ENODEV;
    desc = (struct power_supply_desc *)psy->desc;
    if (!desc || !desc->set_property)
        return -EOPNOTSUPP;
    prop.intval = val;
    ret = power_supply_set_property(psy, psp, &prop);
    return ret;
}

static int apply_targets_locked(void)
{
    int ret = 0, rc;
    struct power_supply *batt = NULL, *usb = NULL;

    /* 应用 PD Verified 设置（若启用且未禁用该特性） */
#if !DISABLE_PD_VERIFED
    if (g_targets.pd_verifed_enabled) {
        rc = set_pd_verifed(g_targets.pd_verifed);
        if (rc && verbose)
            pr_info("chg_param_override: set pd_verifed failed %d\n", rc);
    }
#endif

    batt = find_psy_by_name(target_batt);
    usb  = find_psy_by_name(target_usb);

    if (batt) {
        if (g_targets.voltage_max_uv > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_VOLTAGE_MAX, g_targets.voltage_max_uv);
            if (rc && verbose)
                pr_info("chg_param_override: set VMAX failed %d\n", rc);
        }
        if (g_targets.constant_charge_current_ua > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_CONSTANT_CHARGE_CURRENT,
                               g_targets.constant_charge_current_ua);
            if (rc && verbose)
                pr_info("chg_param_override: set CCC failed %d\n", rc);
        }
        if (g_targets.term_current_ua > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_CHARGE_TERM_CURRENT,
                               g_targets.term_current_ua);
            if (rc && verbose)
                pr_info("chg_param_override: set TERM failed %d\n", rc);
        }
        if (g_targets.charge_control_limit_percent > 0) {
            rc = write_psy_int(batt, POWER_SUPPLY_PROP_CHARGE_CONTROL_LIMIT,
                               g_targets.charge_control_limit_percent);
            if (rc && verbose)
                pr_info("chg_param_override: set charge_control_limit failed %d\n", rc);
        }
    }

    if (usb) {
        if (g_targets.usb_input_current_limit_ua > 0) {
            rc = write_psy_int(usb, POWER_SUPPLY_PROP_INPUT_CURRENT_LIMIT,
                               g_targets.usb_input_current_limit_ua);
            if (rc && verbose)
                pr_info("chg_param_override: set ICL failed %d\n", rc);
        }
    }
    if (batt)
        power_supply_put(batt);
    if (usb)
        power_supply_put(usb);
    return ret;
}

/* ========== procfs 接口 ========== */
#include <linux/proc_fs.h>

static struct proc_dir_entry *proc_entry;

static ssize_t proc_read(struct file *file, char __user *buf, size_t count, loff_t *ppos)
{
    char kbuf[512];
    int len;
    if (*ppos)
        return 0;
    mutex_lock(&g_lock);
    len = scnprintf(kbuf, sizeof(kbuf),
        "batt=%s usb=%s\n"
        "voltage_max=%d\n"
        "ccc=%d\n"
        "term=%d\n"
        "icl=%d\n"
        "auto_reapply=%s\n",
        target_batt, target_usb,
        g_targets.voltage_max_uv,
        g_targets.constant_charge_current_ua,
        g_targets.term_current_ua,
        g_targets.usb_input_current_limit_ua,
        auto_reapply ? "yes" : "no");
    mutex_unlock(&g_lock);
    if (len > count)
        len = count;
    if (copy_to_user(buf, kbuf, len))
        return -EFAULT;
    *ppos += len;
    return len;
}

static int parse_kv(const char *key, const char *val)
{
    int v;
    if (!strcmp(key, "voltage_max") && kstrtoint(val, 10, &v) == 0) {
        g_targets.voltage_max_uv = v;
    } else if ((!strcmp(key, "constant_charge_current") || !strcmp(key, "ccc")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.constant_charge_current_ua = v;
    } else if ((!strcmp(key, "term") || !strcmp(key, "charge_term_current")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.term_current_ua = v;
    } else if ((!strcmp(key, "icl") || !strcmp(key, "input_current_limit")) && kstrtoint(val, 10, &v) == 0) {
        g_targets.usb_input_current_limit_ua = v;
    } else if ((!strcmp(key, "charge_limit") || !strcmp(key, "charge_control_limit")) && kstrtoint(val, 10, &v) == 0) {
        if (v >= 0 && v <= 100) {
            g_targets.charge_control_limit_percent = v;
        } else {
            return -EINVAL;
        }
    } else if (!strcmp(key, "pd_verifed") && kstrtoint(val, 10, &v) == 0) {
        if (v == 0 || v == 1) {
            g_targets.pd_verifed = v;
            g_targets.pd_verifed_enabled = true;
        } else {
            return -EINVAL;
        }
    } else if (!strcmp(key, "pd_verifed_disable")) {
        g_targets.pd_verifed_enabled = false;
    } else if (!strcmp(key, "batt")) {
        strlcpy(target_batt, val, sizeof(target_batt));
    } else if (!strcmp(key, "usb")) {
        strlcpy(target_usb, val, sizeof(target_usb));
    } else {
        return -EINVAL;
    }
    return 0;
}

static ssize_t proc_write(struct file *file, const char __user *buf, size_t count, loff_t *ppos)
{
    char *kbuf, *line, *kv, *val;
    int rc = 0;
    if (count == 0 || count > PAGE_SIZE)
        return -EINVAL;
    kbuf = kzalloc(count + 1, GFP_KERNEL);
    if (!kbuf)
        return -ENOMEM;
    if (copy_from_user(kbuf, buf, count)) {
        kfree(kbuf);
        return -EFAULT;
    }
    mutex_lock(&g_lock);
    line = strim(kbuf);
    while (line && *line) {
        kv = strsep(&line, "\n");
        if (!kv)
            break;
        kv = strim(kv);
        if (!*kv)
            continue;
        val = strchr(kv, '=');
        if (!val) {
            rc = -EINVAL;
            break;
        }
        *val = '\0';
        val++;
        rc = parse_kv(kv, val);
        if (rc)
            break;
    }
    if (!rc)
        rc = apply_targets_locked();
    mutex_unlock(&g_lock);
    kfree(kbuf);
    if (rc)
        return rc;
    return count;
}

static const struct proc_ops proc_fops = {
    .proc_read  = proc_read,
    .proc_write = proc_write,
};

/* 监控和自动重新应用功能 */
static struct timer_list monitor_timer;

static void monitor_timer_callback(struct timer_list *t)
{
    /* 在禁用 PD 控制的构建中避免未使用变量 */
#if !DISABLE_PD_VERIFED
    int current_pd_verifed;
#endif
    
    if (!g_targets.pd_verifed_enabled || !auto_reapply) {
        mod_timer(&monitor_timer, jiffies + msecs_to_jiffies(5000));
        return;
    }
#if !DISABLE_PD_VERIFED
    current_pd_verifed = get_pd_verifed();
    if (current_pd_verifed >= 0) {
        mutex_lock(&g_lock);
        if (current_pd_verifed != g_targets.pd_verifed && 
            g_targets.last_pd_verifed == g_targets.pd_verifed) {
            // pd_verifed 被重置了（通常是插拔充电线），重新应用设置
            if (verbose)
                pr_info("chg_param_override: pd_verifed reset detected (%d->%d), reapplying settings\n",
                        g_targets.pd_verifed, current_pd_verifed);
            apply_targets_locked();
        }
        mutex_unlock(&g_lock);
    }
#endif
    
    // 每5秒检查一次
    mod_timer(&monitor_timer, jiffies + msecs_to_jiffies(5000));
}

/* ========== 可选：在 show/get_property 路径覆盖显示值，确保用户可读到生效值 ========== */
struct ps_show_args {
    struct device *dev;
    struct device_attribute *da;
    char *buf;
};
static struct kretprobe ps_show_kretprobe;

/* 针对 qti_battery_charger 的 pd_verifed_show：强制读取为 1 */
struct class_show_args {
    void *cls;
    void *attr;
    char *buf;
};
static struct kretprobe pd_show_kretprobe;

static int show_entry_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct ps_show_args *args = (struct ps_show_args *)ri->data;
    args->dev = (struct device *)regs->regs[0];
    args->da  = (struct device_attribute *)regs->regs[1];
    args->buf = (char *)regs->regs[2];
#endif
    return 0;
}

static int show_ret_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct ps_show_args *args = (struct ps_show_args *)ri->data;
    struct power_supply *psy;
    const char *name = NULL;
    const char *attr;
    int v;
    if (!args || !args->dev || !args->da || !args->buf)
        return 0;
    attr = args->da->attr.name;
    psy = dev_get_drvdata(args->dev);
    if (psy && psy->desc)
        name = psy->desc->name;
    /* no-op guard retained for clarity; fix logical-not precedence warning by simplifying */
    if (!name) {
        /* nothing */
    }

    mutex_lock(&g_lock);
    if (name && !strcmp(name, target_batt)) {
        if (!strcmp(attr, "voltage_max") && g_targets.voltage_max_uv > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.voltage_max_uv);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        } else if (!strcmp(attr, "constant_charge_current") && g_targets.constant_charge_current_ua > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.constant_charge_current_ua);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        } else if ((!strcmp(attr, "charge_termination_current") || !strcmp(attr, "charge_term_current"))
                   && g_targets.term_current_ua > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.term_current_ua);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        }
    } else if (name && !strcmp(name, target_usb)) {
        if (!strcmp(attr, "input_current_limit") && g_targets.usb_input_current_limit_ua > 0) {
            v = scnprintf(args->buf, PAGE_SIZE, "%d\n", g_targets.usb_input_current_limit_ua);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)v;
#endif
        }
    }
    mutex_unlock(&g_lock);
    return 0;
}

/* pd_verifed_show 入口：保存 buf 指针 */
static int pd_show_entry(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct class_show_args *args = (struct class_show_args *)ri->data;
    args->cls  = (void *)regs->regs[0];
    args->attr = (void *)regs->regs[1];
    args->buf  = (char *)regs->regs[2];
#endif
    return 0;
}

/* pd_verifed_show 返回：强制写入 "1\n" 并覆盖返回长度 */
static int pd_show_ret(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct class_show_args *args = (struct class_show_args *)ri->data;
    int v;
    if (!args || !args->buf)
        return 0;
    v = scnprintf(args->buf, PAGE_SIZE, "1\n");
#if defined(CONFIG_ARM64)
    regs->regs[0] = (unsigned long)v;
#endif
    return 0;
}

static int __init chg_override_init(void)
{
    int ret;
    
    /* 初始化目标结构 */
    memset(&g_targets, 0, sizeof(g_targets));
    
    proc_entry = proc_create("chg_param_override", 0666, NULL, &proc_fops);
    if (!proc_entry)
        return -ENOMEM;

    memset(&ps_show_kretprobe, 0, sizeof(ps_show_kretprobe));
    ps_show_kretprobe.handler = show_ret_handler;
    ps_show_kretprobe.entry_handler = show_entry_handler;
    ps_show_kretprobe.data_size = sizeof(struct ps_show_args);
    ps_show_kretprobe.maxactive = 32;
    ps_show_kretprobe.kp.symbol_name = "power_supply_show_property";
    ret = register_kretprobe(&ps_show_kretprobe);
    if (ret) {
        pr_err("chg_param_override: register show kretprobe failed %d\n", ret);
        remove_proc_entry("chg_param_override", NULL);
        return ret;
    }

    /* 注册 pd_verifed_show 覆盖 */
    memset(&pd_show_kretprobe, 0, sizeof(pd_show_kretprobe));
    pd_show_kretprobe.handler = pd_show_ret;
    pd_show_kretprobe.entry_handler = pd_show_entry;
    pd_show_kretprobe.data_size = sizeof(struct class_show_args);
    pd_show_kretprobe.maxactive = 16;
    pd_show_kretprobe.kp.symbol_name = "pd_verifed_show";
    ret = register_kretprobe(&pd_show_kretprobe);
    if (ret) {
        unregister_kretprobe(&ps_show_kretprobe);
        remove_proc_entry("chg_param_override", NULL);
        pr_err("chg_param_override: register pd_show kretprobe failed %d\n", ret);
        return ret;
    }

    /* 初始化监控定时器 */
    timer_setup(&monitor_timer, monitor_timer_callback, 0);
    mod_timer(&monitor_timer, jiffies + msecs_to_jiffies(5000));

    /* 注册 power_supply 通知与延迟工作 */
    INIT_DELAYED_WORK(&reapply_work, reapply_work_fn);
    psy_nb.notifier_call = psy_event_handler;
    ret = power_supply_reg_notifier(&psy_nb);
    if (ret) {
        del_timer_sync(&monitor_timer);
        unregister_kretprobe(&ps_show_kretprobe);
        remove_proc_entry("chg_param_override", NULL);
        pr_err("chg_param_override: reg notifier failed %d\n", ret);
        return ret;
    }

#if !DISABLE_PD_VERIFED
    pr_info("chg_param_override: loaded batt=%s usb=%s pd_path=%s\n", 
            target_batt, target_usb, pd_verifed_path);
#else
    pr_info("chg_param_override: loaded batt=%s usb=%s (pd_control=disabled)\n", 
            target_batt, target_usb);
#endif
    return 0;
}

static void __exit chg_override_exit(void)
{
    power_supply_unreg_notifier(&psy_nb);
    cancel_delayed_work_sync(&reapply_work);
    del_timer_sync(&monitor_timer);
    unregister_kretprobe(&pd_show_kretprobe);
    unregister_kretprobe(&ps_show_kretprobe);
    remove_proc_entry("chg_param_override", NULL);
    pr_info("chg_param_override: unloaded\n");
}

MODULE_LICENSE("GPL");
MODULE_AUTHOR("serein-213");
MODULE_DESCRIPTION("Override charger params with PD protocol control via power_supply and procfs");

module_init(chg_override_init);
module_exit(chg_override_exit);




