package com.github.gitversiontracker

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object FlowTagsIcons {
    @JvmField
    val FlowTags: Icon = IconLoader.getIcon("/icons/flowTags.svg", FlowTagsIcons::class.java)

    @JvmField
    val Hotfix: Icon = IconLoader.getIcon("/icons/hotfix.svg", FlowTagsIcons::class.java)

    @JvmField
    val Release: Icon = IconLoader.getIcon("/icons/release.svg", FlowTagsIcons::class.java)

    @JvmField
    val Feature: Icon = IconLoader.getIcon("/icons/feature.svg", FlowTagsIcons::class.java)
}
