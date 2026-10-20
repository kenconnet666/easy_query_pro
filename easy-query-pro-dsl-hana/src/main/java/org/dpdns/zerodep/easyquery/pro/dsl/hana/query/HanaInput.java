package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import java.util.Objects;

/**
 * HANA 查询来源的输入参数声明，将名称、Java 值类型和省略/NULL 策略绑定在一起。
 *
 * <p>required 表示应用必须显式传值，optional 表示省略时由数据库模型决定默认行为。
 * 是否省略与是否传 SQL NULL 是两个独立条件；只有 nullable() 声明允许显式绑定 NULL。
 * 名称使用模型中的裸名称（例如 P_NAME），不包含计算视图占位符的 $$ 包装。
 *
 * <p>此处的 Class 校验不替代数据库类型转换，也不自动调用某个实体列的 converter。
 * 自定义参数值必须有该 client 已注册的 JDBC handler；参数实例应与来源定义共用。
 */
public class HanaInput<T> {
    private final String name;
    private final Class<T> type;
    private final boolean required;
    private final boolean nullable;

    protected HanaInput(String name, Class<T> type, boolean required, boolean nullable) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        if (name.isBlank() || name.indexOf('\0') >= 0 || name.contains("$$"))
            throw new IllegalArgumentException("Use a nonblank bare HANA input name without NUL or $$");
        if (type.isPrimitive()) throw new IllegalArgumentException("HANA input declarations require boxed Java types");
        this.required = required;
        this.nullable = nullable;
    }

    /**
     * 缺少此参数时在执行 SQL 之前报错；默认拒绝显式 NULL。
     */
    public static <T> HanaInput<T> required(String name, Class<T> type) {
        return new HanaInput<>(name, type, true, false);
    }

    /**
     * 允许省略参数并交给数据库默认值处理；省略不会生成一个值为 NULL 的占位符。
     */
    public static <T> HanaInput<T> optional(String name, Class<T> type) {
        return new HanaInput<>(name, type, false, false);
    }

    /**
     * 返回允许显式 NULL 的声明；应先完成此配置，再把声明交给来源定义。
     * 非 NULL 仍委托原声明的验证，因此子类的取值范围不会因为改变 NULL 策略而丢失。
     */
    public HanaInput<T> nullable() {
        if (nullable) return this;
        var original = this;
        return new HanaInput<>(name, type, required, true) {
            @Override
            protected void validate(T value) {
                if (value != null) original.validate(value);
            }
        };
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isNullable() {
        return nullable;
    }

    /**
     * 保持 T 的编译约束，并在 raw generic/反射调用绕过编译器时再次核查运行时值类型。
     */
    public Binding<T> bind(T value) {
        return new Binding<>(this, value);
    }

    /** 子类可补充业务值域检查，覆盖时先调用 super 保留基本类型与 NULL 校验。 */
    protected void validate(T value) {
        if (value == null && !nullable)
            throw new IllegalArgumentException("HANA input " + name + " does not allow SQL NULL");
        if (value != null && !type.isInstance(value))
            throw new IllegalArgumentException("HANA input " + name + " requires " + type.getName());
    }

    /**
     * 一次查询的参数赋值。绑定只保存值引用，不深复制业务对象；构建后不要修改传入的可变值。
     * 公共构造器与 bind 使用相同校验，避免直接创建 Binding 绕过声明的 NULL/类型契约。
     */
    public record Binding<T>(HanaInput<T> input, T value) {
        public Binding {
            Objects.requireNonNull(input, "input").validate(value);
        }
    }
}
