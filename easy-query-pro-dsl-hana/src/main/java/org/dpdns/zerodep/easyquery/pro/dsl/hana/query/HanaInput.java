package org.dpdns.zerodep.easyquery.pro.dsl.hana.query;

import java.util.List;
import java.util.Objects;

/**
 * HANA 查询来源的输入参数声明，将名称、Java 值类型和省略/NULL 策略绑定在一起。
 *
 * <p>required 表示应用必须显式传值，optional 表示省略时由数据库模型决定默认行为。
 * 是否省略与是否传 SQL NULL 是两个独立条件；只有 nullable() 声明允许显式绑定 NULL。
 * 名称使用模型中的裸名称（例如 P_NAME），不包含计算视图占位符的 $$ 包装。
 *
 * <p>此处的 Class 校验不替代数据库类型转换，也不自动调用某个实体列的 converter。
 * 默认按原值选择该 client 的 JDBC handler；子类可通过 toJdbcValue 转换业务值或模型协议。
 * 参数实例应与来源定义共用，编码不会自动继承输出实体列的 converter。
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
     * 必填 NVARCHAR 多值模型输入；每个字符串独立引用，整个列表编码为一个 JDBC 字符串。
     * 空列表和 NULL 元素拒绝，List.of("") 则明确表示一个空字符串元素。
     * 列表入口不支持 nullable；当前 HANA 2.0 的 NULL 列表会展开为无效的 IN ()。
     */
    public static HanaInput<List<String>> requiredTextList(String name) {
        return HanaListInput.text(name, true);
    }

    /**
     * 可省略的文本多值输入；省略采用模型默认值，不把空列表解释为默认值。
     */
    public static HanaInput<List<String>> optionalTextList(String name) {
        return HanaListInput.text(name, false);
    }

    /**
     * 必填数值多值输入；元素类型保持 N，只开放整数包装类、BigInteger 与 BigDecimal。
     */
    public static <N extends Number> HanaInput<List<N>> requiredNumericList(String name, Class<N> elementType) {
        return HanaListInput.numbers(name, elementType, true);
    }

    /**
     * 可省略的数值多值输入；模型的精度/范围约束仍由数据库最终校验。
     */
    public static <N extends Number> HanaInput<List<N>> optionalNumericList(String name, Class<N> elementType) {
        return HanaListInput.numbers(name, elementType, false);
    }

    /**
     * 返回允许显式 NULL 的声明；应先完成此配置，再把声明交给来源定义。
     * 非 NULL 仍委托原声明的验证，因此子类的取值范围不会因为改变 NULL 策略而丢失。
     *
     * @throws UnsupportedOperationException 多值列表声明没有可用的 NULL 模型协议
     */
    public HanaInput<T> nullable() {
        if (nullable) return this;
        var original = this;
        return new HanaInput<>(name, type, required, true) {
            @Override
            protected void validate(T value) {
                if (value != null) original.validate(value);
            }

            @Override
            protected Object toJdbcValue(T value) {
                return original.toJdbcValue(value);
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

    /**
     * 子类可补充业务值域检查，覆盖时先调用 super 保留基本类型与 NULL 校验。
     */
    protected void validate(T value) {
        if (value == null && !nullable)
            throw new IllegalArgumentException("HANA input " + name + " does not allow SQL NULL");
        if (value != null && !type.isInstance(value))
            throw new IllegalArgumentException("HANA input " + name + " requires " + type.getName());
    }

    /**
     * 将非 NULL 应用值转为一个 JDBC 值。默认沿用原值，多值模型在这里编码为字符串。
     * 覆盖时只能返回一个非 NULL 参数值，不能修改来源 SQL；构造查询时只调用一次，clone 复用已绑定参数。
     * 显式 NULL 不经过编码，nullable 装饰器仍保留原声明的编码规则。
     */
    protected Object toJdbcValue(T value) {
        return value;
    }

    /**
     * 一次查询的参数赋值。绑定只保存值引用，不深复制业务对象；构建后不要修改传入的可变值。
     * 公共构造器与 bind 使用相同校验，避免直接创建 Binding 绕过声明的 NULL/类型契约。
     */
    public record Binding<T>(HanaInput<T> input, T value) {
        public Binding {
            Objects.requireNonNull(input, "input").validate(value);
        }

        Object jdbcValue() {
            return value == null ? null : Objects.requireNonNull(input.toJdbcValue(value), "HANA input encoder result");
        }
    }
}
