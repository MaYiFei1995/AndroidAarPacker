# AndroidAarPacker

合并本地Jar与Aar到新的AAR中

## 使用

#### 第一步，添加插件

##### 插件发布到 MavenLocal

执行 `gradle :plguin/publishToMavenLocal`，发布到本地仓库

##### 添加本地Maven仓库到插件中并增加插件

7.0 配置`settings.gradle`和`library/build.gradle`

```groovy
pluginManagement {

    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "com.mai.aar-packer") {
                useModule("com.mai.aar-packer:aar-packer:${requested.version}")
            }
        }
    }

    repositories {
        mavenLocal()
        // ...
    }
}
```

```groovy
plugins {
    id 'com.mai.aar-packer.aar-packer' version '1.0'
}
```

低版本配置为

```groovy
buildscript {
    repositories {
        mavenLocal()
    }
    dependencies {
        classpath 'com.mai.aar-packer:aar-packer:aar-packer:1.1.0'
    }
}
```

```groovy
apply plugin: 'com.mai.aar-packer'
```

* `:plugin`为插件源码，支持`Gradle-7.3.3`、`build-tools:7.2.2`，其他版本未测试，需要根据错误调整目录等信息

#### 第二步，配置aarpacker属性

```groovy
aarpacker {
    verboseLog true // 是否打印日志
    ignoreAndroidSupport true   // 忽略AndroidSupport/Androidx
    ignoreDependencies 'com.android.support:appcompat-v7:28.0.0', 'junit:junit:4.12', 'androidx.test.ext:junit:1.1.1', 'androidx.test.espresso:espresso-core:3.2.0', '^com.android.*'
    // ignore
}
```

#### 第三步，配置需要合并的AAR

```groovy
dependencies {
    // allFlavor
    embedded(name: 'aar_file_a', ext: 'aar')
    // normal
    flavorAEmbedded(name: 'aar_file_b', ext: 'aar')
    // special
    flavorBEmbedded(name: 'aar_file_c', ext: 'aar')
}
```

#### 第四步，执行assemble命令

按照flavor执行assemble命令，输出在`build/output/aar/`

**使用方法详见[[:sample]](./sample/build.gradle)**

## 特性

##### 支持 productFlavors 配置

可根据不同的 flavorName 配置需要合并的内容，不需要通过在 dependencies 中判断 taskName 选择是否 embedded

##### 保留 ${applicationId}

在合并 `AndroidManifest` 前替换为`PLACE_HOLDER`，再合并后还原`${applicationId}`，解决 mergeAndroidManifest 时`MergeType`为`APPLICATION`导致的占位符变为包名

##### 支持合并R.txt和为不存在的资源创建 R.class

可以指定lib，生成不存在的 R field，并合并 R.txt，防止出现硬编码当前 package 以外资源导致的 NoSuchField 错误

## 致谢

* [cpdroid/fat-aar](https://github.com/cpdroid/fat-aar)
