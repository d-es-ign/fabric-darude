#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <moduleName> <minecraftVersion> <yarnMappings> <fabricVersion> <minecraftDepRange>"
  exit 1
fi

module_name=$1
minecraft_version=$2
yarn_mappings=$3
fabric_version=$4
minecraft_dep_range=$5

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)

if [[ ! $module_name =~ ^mc ]]; then
  echo "Module name must start with 'mc' (e.g. mc121)"
  exit 1
fi

module_dir="$repo_root/$module_name"
if [[ -d $module_dir ]]; then
  echo "Module directory '$module_dir' already exists"
  exit 1
fi

band_suffix=${module_name#mc}
if [[ -z $band_suffix ]]; then
  echo "Could not derive band suffix from module name"
  exit 1
fi

mkdir -p "$module_dir/src/main/java/com/darude/platform/v${band_suffix}"
mkdir -p "$module_dir/src/main/java/com/darude"
mkdir -p "$module_dir/src/test/java/com/darude"

cat <<EOF > "$module_dir/build.gradle"
def inheritedProps = new Properties()
file('../gradle.properties').withInputStream { inheritedProps.load(it) }
def prop = { String key ->
    project.findProperty(key) ?: rootProject.findProperty(key) ?: inheritedProps.getProperty(key)
}

group = prop('maven_group')
version = prop('mod_version')

buildscript {
    repositories {
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        mavenCentral()
    }
    dependencies {
        classpath "net.fabricmc:fabric-loom:1.14.10"
    }
}

apply plugin: 'fabric-loom'
apply plugin: 'maven-publish'

ext.bandSuffix = '${module_name}'
ext.minecraftVersion = prop('minecraft_version_${band_suffix}')
ext.yarnMappings = prop('yarn_mappings_${band_suffix}')
ext.fabricVersion = prop('fabric_version_${band_suffix}')
ext.minecraftDepRange = prop('minecraft_dep_range_${band_suffix}')
ext.loaderVersion = prop('loader_version')
ext.junitVersion = prop('junit_jupiter_version')
ext.archivesBaseName = prop('archives_base_name')
ext.sharedModule = ':shared-mc-${band_suffix}'

apply from: rootProject.file('gradle/mc-band.gradle')
EOF

cat <<EOF > "$module_dir/settings.gradle"
pluginManagement {
    repositories {
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = 'darude-${module_name}'

include('common')
include('shared-mc-${band_suffix}')

project(':common').projectDir = file('../common')
project(':shared-mc-${band_suffix}').projectDir = file('../shared-mc-${band_suffix}')
EOF

cat <<EOF > "$module_dir/src/main/java/com/darude/DarudeMod.java"
package com.darude;

import com.darude.common.DarudeCommonBootstrap;
import com.darude.platform.v${band_suffix}.DarudePlatformAdapter${band_suffix};
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DarudeMod implements ModInitializer {
    public static final String MOD_ID = "darude";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        String versionBand = DarudeCommonBootstrap.initialize(new DarudePlatformAdapter${band_suffix}());
        LOGGER.info("Darude initialized: sandstorms, renewable sand and sand layers [{}]", versionBand);
    }
}
EOF

cat <<EOF > "$module_dir/src/main/java/com/darude/platform/v${band_suffix}/DarudePlatformAdapter${band_suffix}.java"
package com.darude.platform.v${band_suffix};

import com.darude.DarudeBlocks;
import com.darude.platform.DarudePlatformAdapter;
import com.darude.worldgen.SandLayerChunkGeneration;
import com.darude.worldgen.SandLayerGenerationConfig;

public final class DarudePlatformAdapter${band_suffix} implements DarudePlatformAdapter {
    @Override
    public void initializeServer() {
        DarudeBlocks.initialize();
        SandLayerGenerationConfig.registerReloadListener();
        SandLayerChunkGeneration.register();
    }

    @Override
    public String versionBand() {
        return "${minecraft_version}";
    }
}
EOF

cat <<EOF > "$module_dir/src/test/java/com/darude/VersionBandTest.java"
package com.darude;

import com.darude.platform.v${band_suffix}.DarudePlatformAdapter${band_suffix};
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionBandTest {
    @Test
    void adapterReportsVersionBand() {
        assertEquals("${minecraft_version}", new DarudePlatformAdapter${band_suffix}().versionBand());
    }
}
EOF

# ensure separation if file already has content
printf '\n' >> "$repo_root/gradle.properties"
cat <<EOF >> "$repo_root/gradle.properties"
# Version band: ${module_name}
minecraft_version_${band_suffix}=${minecraft_version}
yarn_mappings_${band_suffix}=${yarn_mappings}
fabric_version_${band_suffix}=${fabric_version}
minecraft_dep_range_${band_suffix}=${minecraft_dep_range}
EOF

echo "Scaffolded module $module_name"
echo "Next steps:" 
echo "1. Add include('$module_name') to settings.gradle"
echo "2. Add '$module_name' to the workflow matrix in .github/workflows/build.yml"
echo "3. Create and wire shared-mc-${band_suffix} baseline module if it does not exist"
