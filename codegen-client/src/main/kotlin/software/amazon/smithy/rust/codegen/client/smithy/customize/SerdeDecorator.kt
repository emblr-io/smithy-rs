/*
* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
* SPDX-License-Identifier: Apache-2.0
*/

package software.amazon.smithy.rust.codegen.client.smithy.customize

import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.DependencyScope
import software.amazon.smithy.rust.codegen.core.rustlang.Feature
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsCustomization
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsSection

/**
 * Decorator that adds the `serde-serialize` and `serde-deserialize` features and serde dependency.
 */
class SerdeDecorator : ClientCodegenDecorator {
    override val name: String = "SerdeDecorator"
    override val order: Byte = 5

    override fun extras(codegenContext: ClientCodegenContext, rustCrate: RustCrate) {
        // Add serde features
        rustCrate.mergeFeature(Feature("serde-serialize", false, listOf("dep:serde")))
        rustCrate.mergeFeature(Feature("serde-deserialize", false, listOf("dep:serde")))
    }

    override fun libRsCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<LibRsCustomization>,
    ): List<LibRsCustomization> =
        baseCustomizations + SerdeLibRsCustomization()
}

class SerdeLibRsCustomization : LibRsCustomization() {
    override fun section(section: LibRsSection) = when (section) {
        is LibRsSection.Body -> writable {
            addDependency(
                CargoDependency.Serde.copy(
                    scope = DependencyScope.Compile,
                    optional = true,
                    features = setOf("derive")
                )
            )
        }
        else -> emptySection
    }
}
