// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.core.bootstrap;

import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.engine.config.flexible.AutoConfigManager;
import org.terasology.engine.context.Context;
import org.terasology.engine.core.module.ModuleManager;
import org.terasology.engine.entitySystem.Component;
import org.terasology.engine.entitySystem.metadata.ComponentLibrary;
import org.terasology.engine.entitySystem.metadata.EntitySystemLibrary;
import org.terasology.engine.entitySystem.metadata.EventLibrary;
import org.terasology.engine.entitySystem.metadata.MetadataUtil;
import org.terasology.engine.entitySystem.prefab.Prefab;
import org.terasology.engine.entitySystem.prefab.internal.PrefabDeltaFormat;
import org.terasology.engine.entitySystem.prefab.internal.PrefabFormat;
import org.terasology.engine.entitySystem.systems.internal.DoNotAutoRegister;
import org.terasology.engine.persistence.typeHandling.RegisterTypeHandler;
import org.terasology.engine.persistence.typeHandling.RegisterTypeHandlerFactory;
import org.terasology.engine.persistence.typeHandling.TypeHandlerLibraryImpl;
import org.terasology.engine.persistence.typeHandling.extensionTypes.CollisionGroupTypeHandler;
import org.terasology.engine.physics.CollisionGroup;
import org.terasology.engine.physics.CollisionGroupManager;
import org.terasology.engine.registry.InjectionHelper;
import org.terasology.gestalt.assets.ResourceUrn;
import org.terasology.gestalt.assets.module.ModuleAwareAssetTypeManager;
import org.terasology.gestalt.module.ModuleEnvironment;
import org.terasology.gestalt.naming.Name;
import org.terasology.gestalt.util.reflection.GenericsUtil;
import org.terasology.persistence.typeHandling.TypeHandler;
import org.terasology.persistence.typeHandling.TypeHandlerFactory;
import org.terasology.persistence.typeHandling.TypeHandlerLibrary;
import org.terasology.reflection.ModuleTypeRegistry;
import org.terasology.reflection.TypeInfo;
import org.terasology.reflection.copy.CopyStrategy;
import org.terasology.reflection.copy.CopyStrategyLibrary;
import org.terasology.reflection.reflect.ReflectFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.Optional;

import static com.google.common.base.Verify.verifyNotNull;

/**
 * Handles an environment switch by updating the asset manager, component library, and other context objects.
 */
public final class EnvironmentSwitchHandler {
    private static final Logger logger = LoggerFactory.getLogger(EnvironmentSwitchHandler.class);

    private PrefabFormat registeredPrefabFormat;
    private PrefabDeltaFormat registeredPrefabDeltaFormat;

    private final Class<?>[] typesWithCopyConstructors = {
            Quaternionf.class,
            Vector2f.class,
            Vector2i.class,
            Vector3f.class,
            Vector3i.class,
            Vector4f.class,
            Vector4i.class,
    };

    public EnvironmentSwitchHandler() {
    }

    public void handleSwitchToGameEnvironment(Context context) {
        ModuleManager moduleManager = context.get(ModuleManager.class);
        ModuleEnvironment environment = moduleManager.getEnvironment();

        ModuleTypeRegistry typeRegistry = context.get(ModuleTypeRegistry.class);
        typeRegistry.reload(environment);

        CopyStrategyLibrary copyStrategyLibrary = context.get(CopyStrategyLibrary.class);
        copyStrategyLibrary.clear();

        for (Class<?> aClass : typesWithCopyConstructors) {
            try {
                ConstructorCopyStrategy.register(copyStrategyLibrary, aClass);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Failed to find copy strategy for {}" + aClass, e);
            }
        }

        //TODO: find a permanent fix over just creating a new typehandler
        // https://github.com/Terasology/JoshariasSurvival/issues/31
        // TypeHandlerLibrary typeHandlerLibrary = context.get(TypeHandlerLibrary.class);
        // typeHandlerLibrary.addTypeHandler(CollisionGroup.class, new CollisionGroupTypeHandler(context.get(CollisionGroupManager.class)));

        TypeHandlerLibrary typeHandlerLibrary = TypeHandlerLibraryImpl.forModuleEnvironment(moduleManager, typeRegistry);
        typeHandlerLibrary.addTypeHandler(CollisionGroup.class, new CollisionGroupTypeHandler(context.get(CollisionGroupManager.class)));
        context.put(TypeHandlerLibrary.class, typeHandlerLibrary);

        // Entity System Library
        EntitySystemLibrary library = new EntitySystemLibrary(context, typeHandlerLibrary);
        context.put(EntitySystemLibrary.class, library);
        ComponentLibrary componentLibrary = library.getComponentLibrary();
        context.put(ComponentLibrary.class, componentLibrary);
        context.put(EventLibrary.class, library.getEventLibrary());
        context.put(ClassMetaLibrary.class, new ClassMetaLibraryImpl(context));

        registerComponents(componentLibrary, environment);
        registerTypeHandlers(context, typeHandlerLibrary, environment);

        // Load configs for the new environment
        AutoConfigManager autoConfigManager = context.get(AutoConfigManager.class);
        autoConfigManager.loadConfigsIn(context);

        ModuleAwareAssetTypeManager assetTypeManager = context.get(ModuleAwareAssetTypeManager.class);

        /*
         * The registering of the prefab formats is done in this method, because it needs to be done before
         * the environment of the asset manager gets changed.
         *
         * It can't be done before this method gets called because the ComponentLibrary isn't
         * existing then yet.
         */
        unregisterPrefabFormats(assetTypeManager);
        registeredPrefabFormat = new PrefabFormat(componentLibrary, typeHandlerLibrary);
        assetTypeManager.getAssetFileDataProducer(assetTypeManager
                .getAssetType(Prefab.class)
                .orElseThrow(() -> new RuntimeException("Cannot get Prefab Asset typee")))
                .addAssetFormat(registeredPrefabFormat);
        registeredPrefabDeltaFormat = new PrefabDeltaFormat(componentLibrary, typeHandlerLibrary);
        assetTypeManager.getAssetFileDataProducer(assetTypeManager
                .getAssetType(Prefab.class)
                .orElseThrow(() -> new RuntimeException("Cannot get Prefab Asset type")))
                .addDeltaFormat(registeredPrefabDeltaFormat);

        assetTypeManager.switchEnvironment(environment);
        assetTypeManager.reloadAssets();

    }

    /**
     * Switches the environment of the asset manager to the specified one. It does not register the prefab formats
     * as they require a proper ComponentLibrary.
     *
     * The existence of this method call is questionable. It has only be introduced to make sure that
     * the asset type manager has never prefab formats that reference an old ComponentLibrary.
     *
     */
    private void cheapAssetManagerUpdate(Context context, ModuleEnvironment environment) {
        ModuleAwareAssetTypeManager moduleAwareAssetTypeManager = context.get(ModuleAwareAssetTypeManager.class);
        unregisterPrefabFormats(moduleAwareAssetTypeManager);
        moduleAwareAssetTypeManager.switchEnvironment(environment);
    }


    public void handleSwitchToPreviewEnvironment(Context context, ModuleEnvironment environment) {
        cheapAssetManagerUpdate(context, environment);
        ComponentLibrary library = new ComponentLibrary(environment, context.get(ReflectFactory.class), context.get(CopyStrategyLibrary.class));
        context.put(ComponentLibrary.class, library);

        registerComponents(library, environment);
    }

    public void handleSwitchBackFromPreviewEnvironment(Context context) {
        // The newly created ComponentLibrary instance cannot be invalidated in context
        ModuleEnvironment environment = context.get(ModuleManager.class).getEnvironment();
        cheapAssetManagerUpdate(context, environment);
    }


    public void handleSwitchToEmptyEnvironment(Context context) {
        ModuleEnvironment environment = context.get(ModuleManager.class).getEnvironment();
        cheapAssetManagerUpdate(context, environment);
    }

    private void unregisterPrefabFormats(ModuleAwareAssetTypeManager assetTypeManager) {
        if (registeredPrefabFormat != null) {
            assetTypeManager.getAssetFileDataProducer(assetTypeManager
                    .getAssetType(Prefab.class)
                    .orElseThrow(() -> new RuntimeException("Cannot get Prefab Asset type")))
                    .removeAssetFormat(registeredPrefabFormat);
            registeredPrefabFormat = null;
        }
        if (registeredPrefabDeltaFormat != null) {
            assetTypeManager.getAssetFileDataProducer(assetTypeManager
                    .getAssetType(Prefab.class)
                    .orElseThrow(() -> new RuntimeException("Cannot get Prefab Asset type")))
                    .removeDeltaFormat(registeredPrefabDeltaFormat);
            registeredPrefabDeltaFormat = null;
        }
    }


    private static void registerComponents(ComponentLibrary library, ModuleEnvironment environment) {
        for (Class<? extends Component> componentType : environment.getSubtypesOf(Component.class)) {
            if (componentType.getAnnotation(DoNotAutoRegister.class) == null) {
                String componentName = MetadataUtil.getComponentClassName(componentType);
                Name componentModuleName = verifyNotNull(environment.getModuleProviding(componentType), "Could not find module for %s %s", componentName, componentType);
                library.register(new ResourceUrn(componentModuleName.toString(), componentName), componentType);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerTypeHandlers(Context context, TypeHandlerLibrary library, ModuleEnvironment environment) {
        for (Class<? extends TypeHandler> handler : environment.getSubtypesOf(TypeHandler.class)) {
            RegisterTypeHandler register = handler.getAnnotation(RegisterTypeHandler.class);
            if (register != null) {
                Optional<Type> opt = GenericsUtil.getTypeParameterBindingForInheritedClass(handler, TypeHandler.class, 0);
                if (opt.isPresent()) {
                    TypeHandler instance = InjectionHelper.createWithConstructorInjection(handler, context);
                    InjectionHelper.inject(instance, context);
                    library.addTypeHandler(TypeInfo.of(opt.get()), instance);
                }
            }
        }

        for (Class<? extends TypeHandlerFactory> clazz : environment.getSubtypesOf(TypeHandlerFactory.class)) {
            if (!clazz.isAnnotationPresent(RegisterTypeHandlerFactory.class)) {
                continue;
            }

            TypeHandlerFactory instance = InjectionHelper.createWithConstructorInjection(clazz, context);
            InjectionHelper.inject(instance, context);
            library.addTypeHandlerFactory(instance);
        }
    }

    /**
     * Create copies by invoking the class's constructor with a value.
     * <p>
     * For classes that offer constructors which make a new instance as a copy of the old one.
     * <p>
     * FIXME: Move this class to a more appropriate package.
     */
    static class ConstructorCopyStrategy<T> implements CopyStrategy<T> {

        private final Constructor<T> declaredConstructor;

        static <T> void register(CopyStrategyLibrary library, Class<T> aClass) throws NoSuchMethodException {
            library.register(aClass, new ConstructorCopyStrategy<>(aClass));
        }

        public ConstructorCopyStrategy(Class<T> aClass) throws NoSuchMethodException {
            declaredConstructor = getConstructorMatching(aClass);
        }

        private Constructor<T> getConstructorMatching(Class<T> aClass) throws NoSuchMethodException {
            try {
                return aClass.getDeclaredConstructor(aClass);
            } catch (NoSuchMethodException e) {
                logger.debug("{} has no constructor for its own class; will check for compatible methods.", aClass);
            }
            for (Constructor<?> constructor : aClass.getDeclaredConstructors()) {
                if (constructor.getParameterCount() != 1) {
                    continue;
                }
                Class<?> parameter = constructor.getParameterTypes()[0];
                if (parameter.isAssignableFrom(aClass)) {
                    //noinspection unchecked
                    return (Constructor<T>) constructor;
                }
            }
            throw new NoSuchMethodException("No compatible constructors found for " + aClass);
        }

        @Override
        public T copy(T value) {
            if (value == null) {
                return null;
            }
            try {
                return declaredConstructor.newInstance(value);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                logger.error("Failure to invoke constructor for {}", value.getClass(), e);
                return null;
            }
        }
    }
}
