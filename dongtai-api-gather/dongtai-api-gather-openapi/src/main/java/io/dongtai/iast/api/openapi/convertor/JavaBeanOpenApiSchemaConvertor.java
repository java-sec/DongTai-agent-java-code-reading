package io.dongtai.iast.api.openapi.convertor;

import io.dongtai.iast.api.openapi.domain.Schema;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 用于转换JavaBean到OpenApi的组件
 *
 * @author CC11001100
 * @since v1.12.0
 */
public class JavaBeanOpenApiSchemaConvertor extends BaseOpenApiSchemaConvertor {

    public JavaBeanOpenApiSchemaConvertor(OpenApiSchemaConvertorManager manager) {
        super(manager);
    }

    @Override
    public String getConvertorName() {
        return "bean-convertor";
    }

    @Override
    public boolean canConvert(Class clazz) {
        return !manager.primitiveTypeConvertor.canConvert(clazz) &&
                !manager.arrayOpenApiSchemaConvertor.canConvert(clazz) &&
                !manager.collectionOpenApiSchemaConvertor.canConvert(clazz) &&
                !manager.enumOpenApiSchemaConvertor.canConvert(clazz);
    }

    /**
     * 把一个符合JavaBean规范的类转换为Open API的schema格式
     *
     * @param clazz
     * @return
     */
    @Override
    public Schema convert(Class clazz) {

        // 先查下组件库，如果有的话就直接返回即可
        Schema schema = manager.database.find(clazz);
        if (schema != null) {
            return schema.direct();
        }

        // TODO 2023-6-16 16:18:56 想一个更合适更容易理解的处理方式
        // 如果在已经发现的类型列表中，则表示正在处理中，则注册一个回调方法
        if (manager.database.exists(clazz)) {
            Schema c = new Schema();
            manager.database.addSchemaConvertDoneCallback(clazz, new Consumer<Schema>() {
                @Override
                public void accept(Schema schema) {
                    c.set$ref(schema.generateRef());
                }
            });
            return c;
        }

        // 这个类没有被发现过，这是第一次处理它，将类标记为已发现，防止DFS的时候陷入环形依赖
        manager.database.addExists(clazz);

        // 然后开始处理它
        Schema c = new Schema();
        // 与类的短名字保持一致，这里会发生名称冲突吗？
        c.setName(clazz.getSimpleName());
        c.setType("object");

        // 处理类上的字段，向上递归处理所有字段，并检查是否符合Bean规范
        parseFieldList(clazz).forEach(new Consumer<Field>() {
            @Override
            public void accept(Field field) {
                Schema schema = convert(clazz, field);
                c.addProperty(field.getName(), schema);
            }
        });

        // 把转换完的组件存储一下
        manager.database.store(clazz, c);

        // 在类处理完的时候触发一下回调
        manager.database.triggerSchemaCallback(clazz, c);

        return c;
    }

    // 此处暂不考虑继承泛型的问题，下个版本再处理它
    private List<Field> parseFieldList(Class clazz) {
        List<Field> allFieldList = new ArrayList<>();
        Set<String> fieldNameSet = new HashSet<>();
        Set<String> getterMethodNameLowercaseSet = new HashSet<>();
        Class currentClass = clazz;
        while (currentClass != null) {

            // 收集类上的字段
            Field[] declaredFields = currentClass.getDeclaredFields();
            for (Field f : declaredFields) {
                if (fieldNameSet.contains(f.getName())) {
                    continue;
                }
                allFieldList.add(f);
                fieldNameSet.add(f.getName());
            }

            // 收集类上的方法名字
            getterMethodNameLowercaseSet.addAll(parseGetterMethodNameLowercaseSet(currentClass));

            currentClass = currentClass.getSuperclass();
        }

        // 然后筛选出来符合条件的字段，作为bean的属性
        List<Field> beanFieldList = new ArrayList<>();
        allFieldList.forEach(new Consumer<Field>() {
            @Override
            public void accept(Field field) {
                if (isBeanField(field, getterMethodNameLowercaseSet)) {
                    beanFieldList.add(field);
                }
            }
        });

        return beanFieldList;
    }

    /**
     * 判断Field是否是bean的field
     *
     * @param field
     * @param getterMethodNameLowercaseSet
     * @return
     */
    private boolean isBeanField(Field field, Set<String> getterMethodNameLowercaseSet) {

        // 采用白名单的方式，public并且是实例方法则认为是可以的
        if (Modifier.isPublic(field.getModifiers())) {
            return !Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers());
        }

        // 私有方法并且有对应的getter
        String setterMethodName = "";
        if (field.getType() == boolean.class || field.getType() == Boolean.class) {
            setterMethodName = "is" + field.getName().toLowerCase();
        } else {
            setterMethodName = "get" + field.getName().toLowerCase();
        }
        return getterMethodNameLowercaseSet.contains(setterMethodName);
    }

    /**
     * 解析类上的getter方法，并将其方法名都转为小写返回
     *
     * @param clazz
     * @return
     */
    private Set<String> parseGetterMethodNameLowercaseSet(Class clazz) {
        Set<String> getterLowercaseMethodNameSet = new HashSet<>();
        for (Method declaredMethod : clazz.getDeclaredMethods()) {
            // 这里采用比较简单的策略，只要是关键字开头的就认为是ok的
            if (declaredMethod.getName().startsWith("get") || declaredMethod.getName().startsWith("is")) {
                getterLowercaseMethodNameSet.add(declaredMethod.getName().toLowerCase());
            }
        }
        return getterLowercaseMethodNameSet;
    }

    @Override
    public Schema convert(Class clazz, Field field) {
        Class fieldClass = field.getType();
        // 因为类型可能是各种类型，所以这里要调用manager上的来路由
        return manager.convertClass(fieldClass);
//        // 如果字段是原生类型，则直接转换即可
//        if (isPrimitiveType(fieldClass)) {
//            return convertPrimitiveType(fieldClass);
//        } else if (fieldClass.isArray()) {
//            // 如果是数组的话，则走数组的转换逻辑
//            return convertArray(fieldClass);
//        } else if () {
//
//        } else {
//
//            // TODO 2023-6-15 18:11:53 处理泛型
//
//            // 如果不是基本类型，则看一下是否已经处理完毕了，如果已经处理完毕了就直接拿来用
//            if (classToComponentMap.containsKey(fieldClass)) {
//                Component refComponent = classToComponentMap.get(fieldClass);
//                if (refComponent.canRef()) {
//                    // 创建一个新的组件，这个新的组件引用已经存在的这个组件
//                    return new Component(refComponent.generateRef());
//                }
//            }
//
//            // 如果是已经存在但是没有处理完毕的，则注册一个回调
//            if (existsClassSet.contains(fieldClass)) {
//                Component c = new Component();
//                if (!componentDoneCallbackMap.containsKey(fieldClass)) {
//                    componentDoneCallbackMap.put(fieldClass, new ArrayList<>());
//                }
//                componentDoneCallbackMap.get(fieldClass).add(new Consumer<Component>() {
//                    @Override
//                    public void accept(Component component) {
//                        // 在处理完毕的时候把当前字段的引用指向这个转换完毕的组件
//                        c.set$ref(component.generateRef());
//                    }
//                });
//                return c;
//            }
//
//            // 如果是一个新的类，则递归处理它
//            // 标记为已经发现过
//            existsClassSet.add(fieldClass);
//            // 递归处理
//            Component c = generate(fieldClass);
//            // 缓存结果
//            cache(fieldClass, c);
//            // 只返回一个引用，并不真的进行嵌套
//            if (c.canRef()) {
//                return new Component(c.generateRef());
//            } else {
//                return c;
//            }
//        }
    }

}