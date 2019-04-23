/**
 * Copyright 2009-2019 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 * 此类表示一组缓存的类定义信息，允许在属性名称和getter / setter方法之间轻松映射。
 *
 * @author Clinton Begin
 */
public class Reflector {

    /**
     * 对应的类
     */
    private final Class<?> type;

    /**
     * 可读属性数组
     */
    private final String[] readablePropertyNames;

    /**
     * 可写属性数组
     */
    private final String[] writablePropertyNames;

    /**
     * 属性对应的setting方法映射
     * key为属性名称
     * value为Invoke对象
     */
    private final Map<String, Invoker> setMethods = new HashMap<>();

    /**
     * 属性对应的getting方法映射
     * key为属性名称
     * value为Invoke对象
     */
    private final Map<String, Invoker> getMethods = new HashMap<>();

    /**
     * 属性对应的setting方法的方法参数类型映射 {@link #setMethods}
     * key为属性名
     * value为方法参数的类型
     */
    private final Map<String, Class<?>> setTypes = new HashMap<>();

    /**
     * 属性对应的getting方法的方法参数类型映射 {@link #getMethods}
     * key为属性名
     * value为返回值的类型
     */
    private final Map<String, Class<?>> getTypes = new HashMap<>();

    /**
     * 默认的构造方法
     */
    private Constructor<?> defaultConstructor;

    /**
     * 不区分大小写的属性集合
     */
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

    public Reflector(Class<?> clazz) {
        //设置对应的类
        type = clazz;

        //初始化defaultConstructor
        addDefaultConstructor(clazz);

        //初始化getMethods和getTypes，通过遍历getting方法
        addGetMethods(clazz);

        //初始化setMethod和setTypes，通过遍历setting方法
        addSetMethods(clazz);

        //初始化getMethods+getTypes和setMethods+setTypes，通过遍历fields属性
        addFields(clazz);

        //初始化readablePropertyNames、writablePropertyNames、caseInsensitivePropertyMap属性
        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        writablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
        for (String propName : readablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    /**
     * 获取默认的无参构造方法
     *
     * @param clazz
     */
    private void addDefaultConstructor(Class<?> clazz) {
        //获取所有的构造方法
        Constructor<?>[] consts = clazz.getDeclaredConstructors();
        //遍历所有的构造方法，查找无参的构造方法
        for (Constructor<?> constructor : consts) {
            //构造器的参数类型个数为0
            if (constructor.getParameterTypes().length == 0) {
                this.defaultConstructor = constructor;
            }
        }
    }

    /**
     * 初始化getMethods和getTypes，通过遍历getting方法
     *
     * @param cls
     */
    private void addGetMethods(Class<?> cls) {
        //属性与其getting方法的映射
        Map<String, List<Method>> conflictingGetters = new HashMap<>();

        //获取所有的方法
        Method[] methods = getClassMethods(cls);

        //遍历所有的方法，查找get方法
        for (Method method : methods) {
            //参数个数大于0，说明不是get方法
            if (method.getParameterTypes().length > 0) {
                continue;
            }

            //方法名
            String name = method.getName();

            //以get/is开头的方法，说明是get方法
            if ((name.startsWith("get") && name.length() > 3) || (name.startsWith("is") && name.length() > 2)) {
                //获得属性名
                name = PropertyNamer.methodToProperty(name);

                //将属性名和方法名加入conflictingGetters
                addMethodConflict(conflictingGetters, name, method);
            }
        }
        //解决getting冲突
        resolveGetterConflicts(conflictingGetters);
    }

    /**
     * 解决getting冲突
     *
     * @param conflictingGetters
     */
    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        //遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 getting 方法
        for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
            //最匹配的方法
            Method winner = null;
            String propName = entry.getKey();
            for (Method candidate : entry.getValue()) {
                //winner为空，表明candidate就是最匹配的方法
                if (winner == null) {
                    winner = candidate;
                    continue;
                }
                //基于返回类型判断
                Class<?> winnerType = winner.getReturnType();
                Class<?> candidateType = candidate.getReturnType();
                //类型相同时
                if (candidateType.equals(winnerType)) {
                    if (!boolean.class.equals(candidateType)) {
                        throw new ReflectionException(
                                "Illegal overloaded getter method with ambiguous type for property " + propName
                                + " in class " + winner.getDeclaringClass()
                                + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                    } else if (candidate.getName().startsWith("is")) {
                        winner = candidate;
                    }
                } else if (candidateType.isAssignableFrom(winnerType)) {
                    // OK getter type is descendant
                } else if (winnerType.isAssignableFrom(candidateType)) {
                    winner = candidate;
                } else {
                    throw new ReflectionException(
                            "Illegal overloaded getter method with ambiguous type for property " + propName
                            + " in class " + winner.getDeclaringClass()
                            + ". This breaks the JavaBeans specification and can cause unpredictable results.");
                }
            }
            addGetMethod(propName, winner);
        }
    }

    /**
     * 初始化getMethods和getTypes
     *
     * @param name
     * @param method
     */
    private void addGetMethod(String name, Method method) {
        //属性名合法才会加入
        if (isValidPropertyName(name)) {
            getMethods.put(name, new MethodInvoker(method));
            //获取返回类型
            Type returnType = TypeParameterResolver.resolveReturnType(method, type);
            getTypes.put(name, typeToClass(returnType));
        }
    }

    /**
     * 初始化setMethod和setTypes，通过遍历setting方法
     *
     * @param cls
     */
    private void addSetMethods(Class<?> cls) {
        //属性与其setting方法的映射
        Map<String, List<Method>> conflictingSetters = new HashMap<>();

        //获取所有的方法
        Method[] methods = getClassMethods(cls);

        //遍历所有方法，查询set方法
        for (Method method : methods) {
            //取出方法名
            String name = method.getName();

            //get方法会以get开头
            if (name.startsWith("set") && name.length() > 3) {
                //get方法参数个数为1
                if (method.getParameterTypes().length == 1) {
                    //取出属性名
                    name = PropertyNamer.methodToProperty(name);

                    //将属性名和方法加入映射关系
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }
        //解决setting方法冲突
        resolveSetterConflicts(conflictingSetters);
    }

    /**
     * 将属性名和方法加入映射关系
     *
     * @param conflictingMethods
     * @param name
     * @param method
     */
    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
        list.add(method);
    }

    /**
     * 解决setting方法冲突
     *
     * @param conflictingSetters
     */
    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        // 遍历每个属性，查找其最匹配的方法。因为子类可以覆写父类的方法，所以一个属性，可能对应多个 setting 方法
        for (String propName : conflictingSetters.keySet()) {
            List<Method> setters = conflictingSetters.get(propName);
            Class<?> getterType = getTypes.get(propName);
            Method match = null;
            ReflectionException exception = null;
            //遍历属性对应的setting方法
            for (Method setter : setters) {
                Class<?> paramType = setter.getParameterTypes()[0];
                if (paramType.equals(getterType)) {
                    // should be the best match
                    match = setter;
                    break;
                }
                if (exception == null) {
                    try {
                        //选择一个更加匹配的set方法
                        match = pickBetterSetter(match, setter, propName);
                    } catch (ReflectionException e) {
                        // there could still be the 'best match'
                        match = null;
                        exception = e;
                    }
                }
            }
            if (match == null) {
                throw exception;
            } else {
                //初始化setMethods和setTypes
                addSetMethod(propName, match);
            }
        }
    }

    /**
     * 选择一个更加匹配的set方法
     *
     * @param setter1
     * @param setter2
     * @param property
     * @return
     */
    private Method pickBetterSetter(Method setter1, Method setter2, String property) {
        if (setter1 == null) {
            return setter2;
        }
        Class<?> paramType1 = setter1.getParameterTypes()[0];
        Class<?> paramType2 = setter2.getParameterTypes()[0];
        if (paramType1.isAssignableFrom(paramType2)) {
            return setter2;
        } else if (paramType2.isAssignableFrom(paramType1)) {
            return setter1;
        }
        throw new ReflectionException(
                "Ambiguous setters defined for property '" + property + "' in class '" + setter2.getDeclaringClass()
                + "' with types '" + paramType1.getName() + "' and '" + paramType2.getName() + "'.");
    }

    /**
     * 初始化setMethods和setTypes
     *
     * @param name
     * @param method
     */
    private void addSetMethod(String name, Method method) {
        //校验属性名合合法
        if (isValidPropertyName(name)) {
            setMethods.put(name, new MethodInvoker(method));
            Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
            setTypes.put(name, typeToClass(paramTypes[0]));
        }
    }

    /**
     * 寻找返回类型对应的类
     *
     * @param src
     * @return
     */
    private Class<?> typeToClass(Type src) {
        Class<?> result = null;
        //普通类型，直接使用类
        if (src instanceof Class) {
            result = (Class<?>) src;

            //泛型类型，使用泛型
        } else if (src instanceof ParameterizedType) {
            result = (Class<?>) ((ParameterizedType) src).getRawType();
            //泛型数组，使用具体类
        } else if (src instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) src).getGenericComponentType();
            if (componentType instanceof Class) {
                result = Array.newInstance((Class<?>) componentType, 0).getClass();
            } else {
                Class<?> componentClass = typeToClass(componentType);
                result = Array.newInstance(componentClass, 0).getClass();
            }
        }

        //都不符合，使用Object类
        if (result == null) {
            result = Object.class;
        }
        return result;
    }

    /**
     * 初始化getMethods+getTypes和setMethods+setTypes，通过遍历fields属性
     *
     * @param clazz
     */
    private void addFields(Class<?> clazz) {
        //获取所有属性
        Field[] fields = clazz.getDeclaredFields();

        //遍历所有属性
        for (Field field : fields) {
            //添加到setMothods和setType中
            if (!setMethods.containsKey(field.getName())) {
                // issue #379 - removed the check for final because JDK 1.5 allows
                // modification of final fields through reflection (JSR-133). (JGB)
                // pr #16 - final static can only be set by the classloader
                int modifiers = field.getModifiers();
                if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                    addSetField(field);
                }
            }
            //添加到getMothods和getType中
            if (!getMethods.containsKey(field.getName())) {
                addGetField(field);
            }
        }
        //递归，处理父类
        if (clazz.getSuperclass() != null) {
            addFields(clazz.getSuperclass());
        }
    }

    /**
     * 将属性添加到setMethods和setTypes
     * @param field
     */
    private void addSetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            setTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 将属性添加到getMethods和getTypes
     * @param field
     */
    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
            getTypes.put(field.getName(), typeToClass(fieldType));
        }
    }

    /**
     * 校验属性名是否合法
     *
     * @param name
     * @return
     */
    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /**
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler <code>Class.getMethods()</code>,
     * because we want to look for private methods as well.
     * 此方法返回一个数组，其中包含在此类和任何超类中声明的所有方法。
     * 我们使用此方法，而不是更简单的 Class.getMethods()，
     * 因为我们也想查找私有方法。
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        //每个方法签名与该方法的映射
        Map<String, Method> uniqueMethods = new HashMap<>();

        //循环类、类的父类，类的父类的父类，直到父类是Object为止
        Class<?> currentClass = cls;
        while (currentClass != null && currentClass != Object.class) {
            //记录当前类定义的方法
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract
            //记录接口中定义的方法
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                //记录当前接口定义的方法
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }
            //获取当前类的父类，继续循环遍历
            currentClass = currentClass.getSuperclass();
        }

        //转换成Method数组返回
        Collection<Method> methods = uniqueMethods.values();

        return methods.toArray(new Method[methods.size()]);
    }

    /**
     * 添加方法数组到uniqueMethods
     *
     * @param uniqueMethods
     * @param methods
     */
    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
            if (!currentMethod.isBridge()) {
                //获取方法签名
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                //当uniqueMethods中不存在该方法签名时，进行添加
                if (!uniqueMethods.containsKey(signature)) {
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    /**
     * 获取方法签名
     *
     * @param method
     * @return
     */
    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        //返回类型
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        //方法名
        sb.append(method.getName());
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /**
     * Checks whether can control member accessible.
     *
     * @return If can control member accessible, it return {@literal true}
     * @since 3.5.0
     */
    public static boolean canControlMemberAccessible() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /**
     * Gets the name of the class the instance provides information for.
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException(
                    "There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException(
                    "There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /**
     * Gets the type for a property setter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException(
                    "There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets the type for a property getter.
     *
     * @param propertyName - the name of the property
     * @return The Class of the property getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException(
                    "There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /**
     * Gets an array of the readable properties for an object.
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /**
     * Gets an array of the writable properties for an object.
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writablePropertyNames;
    }

    /**
     * Check to see if a class has a writable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /**
     * Check to see if a class has a readable property by name.
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
