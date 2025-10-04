#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/kprobes.h>
#include <linux/device.h>
#include <linux/power_supply.h>
#include <linux/string.h>

/*
 * batt_design_override: 通过 kretprobe 拦截 power_supply_get_property，
 * 在查询 POWER_SUPPLY_PROP_CHARGE_FULL_DESIGN / ENERGY_FULL_DESIGN / MODEL_NAME 时返回自定义值。
 * （本文件从主仓库复制，用于导出最小构建仓库）
 */

static char batt_name[64] = "battery";
module_param_string(batt_name, batt_name, sizeof(batt_name), 0644);
MODULE_PARM_DESC(batt_name, "Target power_supply name (default: battery)");

static bool override_any = false; /* 忽略名称匹配覆盖 */
module_param(override_any, bool, 0644);
MODULE_PARM_DESC(override_any, "Override any power_supply (default: false)");

static bool verbose = true;
module_param(verbose, bool, 0644);
MODULE_PARM_DESC(verbose, "Verbose logging (default: true)");

static unsigned long long design_uah = 0; /* 0 不覆盖 */
module_param(design_uah, ullong, 0644);
MODULE_PARM_DESC(design_uah, "Design capacity uAh (0=no override)");

static unsigned long long design_uwh = 0; /* energy 覆盖 */
module_param(design_uwh, ullong, 0644);
MODULE_PARM_DESC(design_uwh, "Design energy uWh (0=no override)");

static char model_name[64] = ""; /* 空不覆盖 */
module_param_string(model_name, model_name, sizeof(model_name), 0644);
MODULE_PARM_DESC(model_name, "Override model_name (empty=no override)");

struct ps_getprop_args { struct power_supply *psy; enum power_supply_property psp; union power_supply_propval *val; };
static struct kretprobe ps_getprop_kretprobe;

struct ps_show_args { struct device *dev; struct device_attribute *da; char *buf; };
static struct kretprobe ps_show_kretprobe;

static int entry_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct ps_getprop_args *args = (struct ps_getprop_args *)ri->data;
    args->psy = (struct power_supply *)regs->regs[0];
    args->psp = (enum power_supply_property)regs->regs[1];
    args->val = (union power_supply_propval *)regs->regs[2];
    if (args->psy && verbose && args->psy->desc) {
        if (args->psp == POWER_SUPPLY_PROP_CHARGE_FULL_DESIGN ||
            args->psp == POWER_SUPPLY_PROP_ENERGY_FULL_DESIGN ||
            args->psp == POWER_SUPPLY_PROP_MODEL_NAME) {
            pr_info("batt_design_override: get_property name=%s psp=%d\n", args->psy->desc->name, args->psp);
        }
    }
#endif
    return 0;
}

static int ret_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct ps_getprop_args *args = (struct ps_getprop_args *)ri->data;
    if (!args || !args->psy || !args->val) return 0;
#if defined(CONFIG_ARM64)
    if (regs_return_value(regs)) return 0;
#endif
    if (args->psp == POWER_SUPPLY_PROP_CHARGE_FULL_DESIGN) {
        if (((override_any && design_uah > 0) || (args->psy->desc && !strcmp(args->psy->desc->name, batt_name) && design_uah > 0))) {
            if (verbose)
                pr_info("batt_design_override: CHARGE_FULL_DESIGN -> %llu uAh (%s)\n", design_uah, args->psy->desc ? args->psy->desc->name : "<null>");
            args->val->intval = (int)design_uah;
        }
    } else if (args->psp == POWER_SUPPLY_PROP_ENERGY_FULL_DESIGN) {
        if (((override_any && design_uwh > 0) || (args->psy->desc && !strcmp(args->psy->desc->name, batt_name) && design_uwh > 0))) {
            if (verbose)
                pr_info("batt_design_override: ENERGY_FULL_DESIGN -> %llu uWh (%s)\n", design_uwh, args->psy->desc ? args->psy->desc->name : "<null>");
            args->val->intval = (int)design_uwh;
        }
    } else if (args->psp == POWER_SUPPLY_PROP_MODEL_NAME) {
        if (((override_any && model_name[0] != '\0') || (args->psy->desc && !strcmp(args->psy->desc->name, batt_name) && model_name[0] != '\0'))) {
            if (verbose)
                pr_info("batt_design_override: MODEL_NAME -> %s (%s)\n", model_name, args->psy->desc ? args->psy->desc->name : "<null>");
            args->val->strval = model_name;
        }
    }
    return 0;
}

static int show_entry_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
#if defined(CONFIG_ARM64)
    struct ps_show_args *args = (struct ps_show_args *)ri->data;
    args->dev = (struct device *)regs->regs[0];
    args->da  = (struct device_attribute *)regs->regs[1];
    args->buf = (char *)regs->regs[2];
    if (verbose && args->da && args->da->attr.name)
        pr_info("batt_design_override: show attr=%s\n", args->da->attr.name);
#endif
    return 0;
}

static int show_ret_handler(struct kretprobe_instance *ri, struct pt_regs *regs)
{
    struct ps_show_args *args = (struct ps_show_args *)ri->data;
    const char *attr;
    struct power_supply *psy;
    const char *name;
    ssize_t newlen;

    if (!args || !args->dev || !args->da || !args->buf)
        return 0;
    attr = args->da->attr.name;
    if (!attr)
        return 0;
    psy = dev_get_drvdata(args->dev);
    name = (psy && psy->desc) ? psy->desc->name : NULL;
    if (!strcmp(attr, "charge_full_design")) {
        if (((override_any && design_uah>0) || (name && !strcmp(name,batt_name) && design_uah>0))) {
            newlen = scnprintf(args->buf, PAGE_SIZE, "%llu\n", design_uah);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)newlen;
#endif
            if (verbose) pr_info("batt_design_override: show charge_full_design %s -> %llu\n", name?name:"<null>", design_uah);
        }
    } else if (!strcmp(attr, "energy_full_design")) {
        if (((override_any && design_uwh>0) || (name && !strcmp(name,batt_name) && design_uwh>0))) {
            newlen = scnprintf(args->buf, PAGE_SIZE, "%llu\n", design_uwh);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)newlen;
#endif
            if (verbose) pr_info("batt_design_override: show energy_full_design %s -> %llu\n", name?name:"<null>", design_uwh);
        }
    } else if (!strcmp(attr, "model_name")) {
        if (((override_any && model_name[0]) || (name && !strcmp(name,batt_name) && model_name[0]))) {
            newlen = scnprintf(args->buf, PAGE_SIZE, "%s\n", model_name);
#if defined(CONFIG_ARM64)
            regs->regs[0] = (unsigned long)newlen;
#endif
            if (verbose) pr_info("batt_design_override: show model_name %s -> %s\n", name?name:"<null>", model_name);
        }
    }
    return 0;
}

static int __init batt_override_init(void)
{
    int ret;
    memset(&ps_getprop_kretprobe, 0, sizeof(ps_getprop_kretprobe));
    ps_getprop_kretprobe.handler = ret_handler;
    ps_getprop_kretprobe.entry_handler = entry_handler;
    ps_getprop_kretprobe.data_size = sizeof(struct ps_getprop_args);
    ps_getprop_kretprobe.maxactive = 32;
    ps_getprop_kretprobe.kp.symbol_name = "power_supply_get_property";
    ret = register_kretprobe(&ps_getprop_kretprobe);
    if (ret) { pr_err("batt_design_override: register get_property kretprobe failed %d\n", ret); return ret; }

    memset(&ps_show_kretprobe, 0, sizeof(ps_show_kretprobe));
    ps_show_kretprobe.handler = show_ret_handler;
    ps_show_kretprobe.entry_handler = show_entry_handler;
    ps_show_kretprobe.data_size = sizeof(struct ps_show_args);
    ps_show_kretprobe.maxactive = 32;
    ps_show_kretprobe.kp.symbol_name = "power_supply_show_property";
    ret = register_kretprobe(&ps_show_kretprobe);
    if (ret) { pr_err("batt_design_override: register show kretprobe failed %d\n", ret); unregister_kretprobe(&ps_getprop_kretprobe); return ret; }

    pr_info("batt_design_override: loaded (batt_name=%s design_uah=%llu design_uwh=%llu model_name=%s)\n", batt_name, design_uah, design_uwh, model_name[0]?model_name:"<none>");
    return 0;
}

static void __exit batt_override_exit(void)
{
    unregister_kretprobe(&ps_getprop_kretprobe);
    unregister_kretprobe(&ps_show_kretprobe);
    pr_info("batt_design_override: unloaded\n");
}

MODULE_LICENSE("GPL");
MODULE_AUTHOR("serein-213");
MODULE_DESCRIPTION("Override battery design capacity via kretprobe (export minimal)");

module_init(batt_override_init);
module_exit(batt_override_exit);
