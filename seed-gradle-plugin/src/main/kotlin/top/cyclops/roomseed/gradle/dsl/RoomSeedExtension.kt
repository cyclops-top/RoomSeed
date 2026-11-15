package top.cyclops.roomseed.gradle.dsl

import org.gradle.api.provider.Property

abstract class RoomSeedExtension {
    /** Overrides the shared seed directory for this module. */
    abstract val seedDir: Property<String>
}
