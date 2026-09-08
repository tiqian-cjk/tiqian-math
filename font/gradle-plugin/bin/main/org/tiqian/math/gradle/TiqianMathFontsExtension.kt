package org.tiqian.math.gradle

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.tiqian.math.core.MathFontClass
import org.tiqian.math.core.MathFontWeight

abstract class TiqianMathFontsExtension @Inject constructor(objects: ObjectFactory) {
    val families: NamedDomainObjectContainer<TiqianMathFontFamily> =
        objects.domainObjectContainer(TiqianMathFontFamily::class.java) { name ->
            objects.newInstance(TiqianMathFontFamily::class.java, name, objects)
        }

    fun family(name: String, configure: Action<in TiqianMathFontFamily>) {
        configure.execute(families.maybeCreate(name))
    }
}
abstract class TiqianMathFontFamily @Inject constructor(
    private val familyName: String,
    objects: ObjectFactory,
) : Named {
    override fun getName(): String = familyName

    abstract val fontClass: Property<MathFontClass>

    val faces: NamedDomainObjectContainer<TiqianMathFontFace> =
        objects.domainObjectContainer(TiqianMathFontFace::class.java) { name ->
            objects.newInstance(TiqianMathFontFace::class.java, name)
        }

    init {
        fontClass.convention(MathFontClass.Serif)
    }

    fun face(name: String, configure: Action<in TiqianMathFontFace>) {
        configure.execute(faces.maybeCreate(name))
    }
}

abstract class TiqianMathFontFace @Inject constructor(
    private val faceName: String,
) : Named {
    override fun getName(): String = faceName

    abstract val source: RegularFileProperty
    abstract val weight: Property<MathFontWeight>

    init {
        weight.convention(MathFontWeight.Regular)
    }
}
