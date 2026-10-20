package org.dpdns.zerodep.easyquery.pro.dsl.type;

/**
 * Plain bounded text; JSON documents and restricted LOBs use their own expression families.
 */
public interface TextType extends CastTarget<String>, StorageType<String> {
    @Override
    default Class<String> javaType() {
        return String.class;
    }
}
