/**
 * Copyright (c) 2016 NumberFour AG.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   NumberFour AG - Initial API and implementation
 */
package org.eclipse.n4js.transpiler.es.tests

import com.google.inject.Inject
import org.eclipse.n4js.N4JSInjectorProviderWithMockProject
import org.eclipse.n4js.N4JSParseHelper
import org.eclipse.n4js.N4JSTestHelper
import org.eclipse.n4js.N4JSValidationTestHelper
import org.eclipse.n4js.generator.GeneratorOption
import org.eclipse.xtext.testing.InjectWith
import org.eclipse.xtext.testing.XtextRunner
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests activation and deactivation of optional transformations in the transpiler via {@link GeneratorOption}s.
 * 
 * NOTE: purpose of these tests is not to test the actual behavior of the optional transformations, just to see if
 * turning them on and off works.
 */
@RunWith(XtextRunner)
@InjectWith(N4JSInjectorProviderWithMockProject)
class GeneratorOptionTest extends AbstractTranspilerTest {

	@Inject protected extension N4JSTestHelper;
	@Inject protected extension N4JSParseHelper;
	@Inject protected extension N4JSValidationTestHelper;

	val snippet = '''
		let b = 'b';
		`a${b}c`;

		async function foo(p1, p2, p3) {
			await bar();
		}
		async function bar() {}

		let arrow1 = ()=>{};
		let arrow2 = ()=>42;

		let x,y;
		({x,y}={x:1,y:2});
	'''

	@Test
	def void testTurnOnOptionalTransformations() throws Exception {
		val script = snippet.parseAndValidateSuccessfully;
		assertCompileResult(script, GeneratorOption.MAX_TRANSPILE_OPTIONS, '''
			// Generated by N4JS transpiler; for copyright see original N4JS source file.
			
			import 'n4js-runtime'
			
			let b = 'b';
			("a" + b + "c");
			function foo(p1, p2, p3) {
				return $spawn(function *() {
					(yield bar());
				}.apply(this, arguments));
			}
			function bar() {
				return $spawn(function *() {}.apply(this, arguments));
			}
			let arrow1 = (function() {}).bind(this);
			let arrow2 = (function() {
				return 42;
			}).bind(this);
			let x, y;
			((function($destructParam0) {
				var $destruct0;
				$destruct0 = ($destructParam0);
				x = $destruct0['x'];
				y = $destruct0['y'];
				return $destruct0;
			})({
				x: 1,
				y: 2
			}));
		''');
	}

	@Test
	def void testTurnOffOptionalTransformations() throws Exception {
		val script = snippet.parseAndValidateSuccessfully;
		assertCompileResult(script, #[], '''
			// Generated by N4JS transpiler; for copyright see original N4JS source file.
			
			import 'n4js-runtime'
			
			let b = 'b';
			`a${b}c`;
			async function foo(p1, p2, p3) {
				await bar();
			}
			async function bar() {}
			let arrow1 = ()=>{};
			let arrow2 = ()=>42;
			let x, y;
			({
				x,
				y
			} = {
				x: 1,
				y: 2
			});
		''');
	}
}
