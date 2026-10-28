# 🔮 HEX Render

[![NeoForge](https://img.shields.io/badge/NeoForge-1.21.1-orange.svg)](https://neoforged.net/)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://adoptium.net/)
[![glTF 2.0](https://img.shields.io/badge/glTF-2.0%20%2F%20GLB-brightgreen.svg)](https://www.khronos.org/gltf/)
[![Shaders](https://img.shields.io/badge/Iris%20%2F%20Oculus-Compatible-purple.svg)](https://irisshaders.dev/)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](https://www.gnu.org/licenses/lgpl-3.0)
[![Architecture](https://img.shields.io/badge/Architecture-Pure%20Java%20(22%20classes)-success.svg)]()

**HEX Render** — высокопроизводительный, независимый и легковесный движок рендера **glTF 2.0** и **GLB** моделей для Minecraft 1.21.1 (NeoForge).

Разработан полностью с нуля: **полный отказ от тяжёлых сторонних библиотек**, сверхкомпактная архитектура из 22 классов, поддержка скелетной анимации, сокетов, нативного освещения Minecraft, шейдерпаков Iris/Oculus и **кинематографичных PBR-бликов**.

---

## ✨ Ключевые возможности

### 🚀 Сверхлёгкий микро-парсер (`GltfMicroParser`)
- **0 внешних зависимостей**: парсер написан на чистой Java и встроенном в Minecraft `Gson` (~440 строк).
- **Поддержка любых glTF**: читает стандартные `.gltf` с Base64-буферами (`data:application/octet-stream;base64`), а также бинарные `.glb` контейнеры.
- **Полная совместимость с Blockbench и Blender**: корректно считывает любые типы индексов и суставов (`GL_UNSIGNED_SHORT`, `GL_UNSIGNED_BYTE`, `GL_INT`, `GL_FLOAT`).
- **Авто-поиск текстур**: текстуры автоматически связываются через Blockbench `extras.resourceLocation` либо по зеркальному имени файла (`textures/item/...`).

### 💎 Кинематографичные PBR-блики (`HexRenderTypes`)
- **Собственный Core Shader** (`rendertype_entity_pbr`): вычисляет зеркальные блики **Blinn-Phong** на фрагментном уровне GPU в реальном времени.
- **Живой металл**: оружие, гранаты и инструменты сочно и реалистично переливаются бликами при вращении и перемещении персонажа.
- **Связь с картой света**: блики ярко играют на солнце и при свете факелов, но реалистично затухают в тёмных пещерах.

### 🛡️ 100% совместимость с шейдерами (Iris / Oculus)
- Отрисовка ведётся через стандартный ванильный конвейер `MultiBufferSource` и `RenderType`.
- Модели корректно попадают в **G-Buffer**: полноценно работают тени, объёмный свет, SSAO, SSR, Motion Blur и пост-обработка шейдерпаков.
- При активных шейдерпаках Iris автоматически перехватывает пайплайн без графических артефактов.

### 🦴 Скелетный скиннинг и анимация (CPU LBS)
- **Linear Blend Skinning (LBS)**: расчёт позиций и нормалей на CPU с пулом памяти `ThreadLocal` — не создаёт мусора для Garbage Collector.
- **Fast-path**: ускоренная ветка вычислений для жестких вершин с единичным весом (`w0 >= 0.999f`).
- **Скелетная интерполяция**: поддержка каналов `translation`, `rotation` (кватернионы) и `scale` со всеми типами интерполяции (`LINEAR`, `STEP`, `CUBICSPLINE`).
- **Система сокетов (`SocketLocator`)**: привязка вспышек выстрела, гильз, партиклов и лучей к костям модели в реальном времени.
- **Математический Anti-Bobbing**: прецизионное подавление колебаний ванильной камеры (`bobView`) для неподвижных прицелов оружия от первого лица.

### ⚡ Асинхронный реестр (`ModelRegistry`)
- Неблокирующая загрузка и парсинг моделей в выделенном пуле потоков.
- Встроенная дедупликация запросов (`CompletableFuture`).
- Автоматическая перезагрузка ассетов при нажатии `F3 + T` через NeoForge `RegisterClientReloadListenersEvent`.

---

## 🏛️ Архитектура движка (22 класса)

```
com.hex.hexrender
│
├── HexRender.java                     # Мод-входная точка NeoForge (регистрация шин)
│
├── client/                            # Пользовательские рендереры
│   ├── GltfItemRenderer.java          # Универсальный рендерер предметов в руках/инвентаре
│   ├── ItemModelDescriptor.java       # Интерфейс описания облика, анимаций и трансформаций
│   ├── GltfArmorRenderer.java         # Рендерер 3D glTF брони на игроках и гуманоидах
│   ├── ArmorModelDescriptor.java      # Интерфейс описания брони
│   └── GltfEntityRenderer.java        # Рендерер сущностей и снарядов в мире
│
├── core/                              # Управление ресурсами
│   ├── ModelRegistry.java             # Асинхронный многопоточный кэш и загрузчик
│   └── ModelHandle.java               # Удобная обёртка для ленивого получения модели
│
├── direct/                            # Мосты графических подсистем
│   └── HexIrisBridge.java             # Интеграция с теневыми проходами Iris
│
├── loader/                            # Загрузка и парсинг
│   ├── GltfMicroParser.java           # Автономный сверхбыстрый парсер glTF/GLB на Gson
│   ├── GltfModelAsset.java            # Иммутабельный контейнер мешей, скелета и клипов
│   └── GltfSceneLoader.java           # Фасад загрузки из ResourceManager
│
├── mesh/                              # Геометрия и материалы
│   ├── SkinnedPrimitive.java          # Вершины, нормали, UV, веса, суставы и индексы
│   └── PrimitiveMaterial.java         # Параметры материала (текстура, альфа, PBR roughness/metallic)
│
├── motion/                            # Анимационный движок
│   ├── SkeletalAnimation.java         # Анимационный клип
│   ├── AnimationTrack.java            # Дорожка трансформации конкретного узла
│   └── MotionBlend.java               # Смешивание анимаций
│
├── render/                            # Отрисовка
│   ├── HexRenderTypes.java            # PBR Core Shader и ванильные RenderType
│   └── ModelSkinningRenderer.java     # LBS скиннинг, освещение и отправка в VertexConsumer
│
└── skeleton/                          # Костная структура
    ├── RigHierarchy.java              # Иерархия костей и inverse bind matrices
    ├── SkeletonPose.java              # Текущее состояние трансформаций узлов
    └── SocketLocator.java             # Локатор сокетов в мировых координатах
```

---

## 🛠️ Примеры использования

### 1. Оружие или предмет в руке

Определите описание предмета:

```java
public class AssaultRifleAppearance implements ItemModelDescriptor {
    private static final ResourceLocation MODEL_ID = 
            ResourceLocation.fromNamespaceAndPath("mymod", "models/item/assault_rifle.gltf");

    @Override
    public GltfModelAsset model(ItemStack stack, ItemDisplayContext context) {
        return ModelRegistry.obtain(MODEL_ID);
    }

    @Override
    public SkeletalAnimation clip(ItemStack stack, ItemDisplayContext context) {
        GltfModelAsset model = model(stack, context);
        if (model == null) return null;
        
        // Воспроизводим анимацию выстрела или перезарядки
        return stack.getOrCreateTag().getBoolean("Shooting") 
                ? model.animation("fire") 
                : model.animation("idle");
    }

    @Override
    public float seconds(ItemStack stack, ItemDisplayContext context, float partialTick) {
        return (stack.getOrCreateTag().getInt("AnimTicks") + partialTick) / 20.0f;
    }

    @Override
    public void transform(ItemStack stack, ItemDisplayContext context, PoseStack poseStack) {
        // Стабилизируем покачивание камеры при беге для прицела
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            GltfItemRenderer.applyAntiBobbing(Minecraft.getInstance().player, poseStack, 
                    Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
            poseStack.translate(0.0f, -0.05f, 0.1f);
        }
    }
}
```

Создайте рендерер предмета:

```java
public class AssaultRifleRenderer extends GltfItemRenderer {
    public AssaultRifleRenderer() {
        super(new AssaultRifleAppearance());
    }
}
```

Подключите рендерер к вашему `Item` через `IClientItemExtensions`:

```java
@Override
public void initializeClient(Consumer<IClientItemExtensions> consumer) {
    consumer.accept(new IClientItemExtensions() {
        private final AssaultRifleRenderer renderer = new AssaultRifleRenderer();

        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return renderer;
        }
    });
}
```

---

### 2. Снаряд или граната в мире (`EntityRenderer`)

```java
public class RocketRenderer extends GltfEntityRenderer<RocketEntity> {
    private static final ResourceLocation MODEL = 
            ResourceLocation.fromNamespaceAndPath("mymod", "models/entity/rocket.gltf");

    public RocketRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected GltfModelAsset getModel(RocketEntity entity) {
        return ModelRegistry.obtain(MODEL);
    }

    @Override
    protected void applyTransform(RocketEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, entity.yRotO, entity.getYRot()) - 90.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, entity.xRotO, entity.getXRot())));
    }
}
```

---

### 3. Трёхмерная glTF броня на игроке (`GltfArmorRenderer`)

```java
public class ExoskeletonArmorRenderer extends GltfArmorRenderer {
    private static final ResourceLocation MODEL = 
            ResourceLocation.fromNamespaceAndPath("mymod", "models/armor/exoskeleton.gltf");

    public ExoskeletonArmorRenderer() {
        super((stack, slot) -> ModelRegistry.obtain(MODEL));
    }
}
```

---

### 4. Использование сокетов костей (дуло, гильзовыбрасыватель)

В Blockbench добавьте пустую кость с префиксом `socket_` (например, `socket_muzzle` или `socket_ejector`). HEX Render автоматически захватит её координаты:

```java
Vector3f muzzlePos = SocketLocator.findSocketWorldPos(
        model, 
        currentPose, 
        "socket_muzzle", 
        itemPoseStack
);
// Спавним частицы выстрела прямо из дула оружия!
level.addParticle(ParticleTypes.FLASH, muzzlePos.x, muzzlePos.y, muzzlePos.z, 0, 0, 0);
```

---

## 🎨 Советы по экспорту моделей из Blockbench

1. **Формат**: Экспортируйте как `glTF 2.0 (.gltf)` или `Binary glTF (.glb)`.
2. **Анимации**: Именуйте клипы понятными именами (`shot`, `reload`, `inspect`, `pin_pull`).
3. **Кости и сетка**:
   - HEX Render поддерживает иерархию костей любой глубины.
   - Имена костей доступны через `model.rig().nodeIndex("bone_name")`.
4. **Текстуры**:
   - По умолчанию текстура подтягивается автоматически из `assets/<modid>/textures/item/<name>.png`.
   - Путь также можно явно зашить в Blockbench через поле `extras.resourceLocation`.

---

## 📦 Сборка и подключение

### Сборка JAR
```bash
./gradlew jar
```
Готовый артефакт появится в `build/libs/hexrender-1.0.0.jar`.

### Подключение в `build.gradle` вашего мода
Поместите `hexrender-1.0.0.jar` в папку `libs/` вашего мода:

```groovy
dependencies {
    implementation files('libs/hexrender-1.0.0.jar')
    jarJar(files('libs/hexrender-1.0.0.jar'))
}
```

---

## 📄 Лицензия

Проект распространяется под лицензией GNU Lesser General Public License v3.0 (LGPL-3.0). Разработано для мода HEX Rift Division и сообщества разработчиков NeoForge.
