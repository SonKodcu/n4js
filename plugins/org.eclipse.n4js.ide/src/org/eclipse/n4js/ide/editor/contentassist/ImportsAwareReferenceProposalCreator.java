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
package org.eclipse.n4js.ide.editor.contentassist;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.n4js.N4JSLanguageConstants;
import org.eclipse.n4js.ide.editor.contentassist.N4JSIdeContentProposalProvider.N4JSCandidateFilter;
import org.eclipse.n4js.n4JS.N4JSPackage;
import org.eclipse.n4js.projectModel.IN4JSCore;
import org.eclipse.n4js.projectModel.IN4JSProject;
import org.eclipse.n4js.resource.N4JSResourceDescriptionStrategy;
import org.eclipse.n4js.scoping.IContentAssistScopeProvider;
import org.eclipse.n4js.services.N4JSGrammarAccess;
import org.eclipse.n4js.ts.scoping.N4TSQualifiedNameProvider;
import org.eclipse.n4js.ts.typeRefs.TypeRefsPackage;
import org.eclipse.n4js.ts.types.TExportableElement;
import org.eclipse.xtext.conversion.IValueConverter;
import org.eclipse.xtext.conversion.IValueConverterService;
import org.eclipse.xtext.ide.editor.contentassist.ContentAssistContext;
import org.eclipse.xtext.ide.editor.contentassist.ContentAssistEntry;
import org.eclipse.xtext.ide.editor.contentassist.IIdeContentProposalAcceptor;
import org.eclipse.xtext.ide.editor.contentassist.IdeContentProposalCreator;
import org.eclipse.xtext.naming.QualifiedName;
import org.eclipse.xtext.resource.IEObjectDescription;
import org.eclipse.xtext.resource.impl.AliasedEObjectDescription;
import org.eclipse.xtext.scoping.IScope;
import org.eclipse.xtext.scoping.IScopeProvider;
import org.eclipse.xtext.util.Arrays;

import com.google.common.base.Predicate;
import com.google.inject.Inject;

/**
 */
@SuppressWarnings("unused")
public class ImportsAwareReferenceProposalCreator {

	@Inject
	private IScopeProvider scopeProvider;

	private IValueConverter<String> valueConverter;

	@Inject
	private IN4JSCore n4jsCore;

	private static final EReference[] referencesSupportingImportedElements = {
			N4JSPackage.Literals.IDENTIFIER_REF__ID,
			TypeRefsPackage.Literals.PARAMETERIZED_TYPE_REF__DECLARED_TYPE
	};

	@Inject
	private void setValueConverter(IValueConverterService service, N4JSGrammarAccess grammarAccess) {
		@SuppressWarnings({ "unchecked", "rawtypes" })
		IValueConverter<String> converter = (IValueConverter) ((IValueConverterService.Introspectable) service)
				.getConverter(grammarAccess
						.getTypeReferenceNameRule()
						.getName());
		this.valueConverter = converter;
	}

	/**
	 * Retrieves possible reference targets from scope, including erroneous solutions (e.g., not visible targets). This
	 * list is further filtered here. This is a general pattern: Do not change or modify scoping for special content
	 * assist requirements, instead filter here.
	 *
	 * @param proposalFactory
	 *            usually this will be an instance of
	 *            {@link AbstractJavaBasedContentProposalProvider.DefaultProposalCreator DefaultProposalCreator}.
	 * @param filter
	 *            by default an instance of {@link N4JSCandidateFilter} will be provided here.
	 */
	@SuppressWarnings("javadoc")
	public void lookupCrossReference(
			EObject model,
			EReference reference,
			ContentAssistContext context,
			IIdeContentProposalAcceptor acceptor,
			Predicate<IEObjectDescription> filter,
			IdeContentProposalCreator proposalFactory) {

		if (model != null) {
			final IContentAssistScopeProvider contentAssistScopeProvider = (IContentAssistScopeProvider) scopeProvider;
			final IScope scope = contentAssistScopeProvider.getScopeForContentAssist(model, reference);
			// iterate over candidates, filter them, and create ICompletionProposals for them
			final Iterable<IEObjectDescription> candidates = scope.getAllElements();

			// don't use candidates.forEach since we want an early exit
			for (IEObjectDescription candidate : candidates) {
				if (!acceptor.canAcceptMoreProposals())
					return;

				if (filter.apply(candidate)) {
					final QualifiedName qfn = candidate.getQualifiedName();
					final int qfnSegmentCount = qfn.getSegmentCount();
					final String tmodule = (qfnSegmentCount >= 2) ? qfn.getSegment(qfnSegmentCount - 2) : null;

					final ContentAssistEntry proposal = getProposal(candidate,
							model,
							scope,
							reference,
							context,
							filter,
							proposalFactory);

					acceptor.accept(proposal, 0);
				}
			}
		}
	}

	/** In case of main module, adjust the qualified name, e.g. index.Element -> react.Element */
	private QualifiedName getCandidateName(IEObjectDescription candidate, String tmodule) {
		QualifiedName candidateName;
		IN4JSProject project = n4jsCore.findProject(candidate.getEObjectURI()).orNull();
		if (project != null && tmodule != null && tmodule.equals(project.getMainModule())) {
			String projectName = project.getProjectName().getRawName();
			String definesPackage = project.getDefinesPackageName().getRawName();
			if (definesPackage != null) {
				projectName = definesPackage;
			}
			String lastSegmentOfQFN = candidate.getQualifiedName().getLastSegment().toString();
			candidateName = QualifiedName.create(projectName, lastSegmentOfQFN);
		} else {
			candidateName = candidate.getQualifiedName();
		}
		return candidateName;
	}

	/**
	 * Creates initial proposal adjusted for the N4JS imports. Then passes that proposal to the provided delegate
	 * proposal factory. Obtained ICompletionProposal is configured with a FQNImporter as custom text. applier.
	 *
	 * @param candidate
	 *            for which proposal is created
	 * @param delegateProposalFactory
	 *            delegate proposal factory
	 * @return code completion proposal
	 */
	private ContentAssistEntry getProposal(IEObjectDescription candidate, EObject model,
			IScope scope,
			EReference reference,
			ContentAssistContext context,
			Predicate<IEObjectDescription> filter,
			IdeContentProposalCreator delegateProposalFactory) {

		final IEObjectDescription inputToUse = getAliasedDescription(candidate, reference, context);
		String name = inputToUse.getName().toString();
		final ContentAssistEntry result = delegateProposalFactory.createProposal(name, context);

		return result;
	}

	/**
	 * Creates proposal taking semantics of the N4JS imports into account.
	 *
	 * @param candidate
	 *            the original input for which we create proposal
	 * @param reference
	 *            the reference
	 * @param context
	 *            the context
	 * @return candidate proposal adjusted to the N4JS imports
	 */
	private IEObjectDescription getAliasedDescription(IEObjectDescription candidate, EReference reference,
			ContentAssistContext context) {

		// Content assist at a location where only simple names are allowed:
		// We found a qualified name and we'd need an import to be allowed to use
		// that name. Consider only the simple name of the element from the index
		// and make sure that the import is inserted as soon as the proposal is applied
		QualifiedName inputQN = candidate.getName();
		int inputNameSegmentCount = inputQN.getSegmentCount();
		if (inputNameSegmentCount > 1
				&& Arrays.contains(referencesSupportingImportedElements, reference))
			return new AliasedEObjectDescription(QualifiedName.create(inputQN.getLastSegment()), candidate);

		// filter out non-importable things:
		// globally provided things should never be imported:
		if (inputNameSegmentCount == 2 && N4TSQualifiedNameProvider.GLOBAL_NAMESPACE_SEGMENT
				.equals(inputQN.getFirstSegment()))
			return new AliasedEObjectDescription(QualifiedName.create(inputQN.getLastSegment()), candidate);

		// special handling for default imports:
		if (inputQN.getLastSegment().equals(N4JSLanguageConstants.EXPORT_DEFAULT_NAME)) {
			if (TExportableElement.class.isAssignableFrom(candidate.getEClass().getInstanceClass())) {
				if (N4JSResourceDescriptionStrategy.getExportDefault(candidate)) {
					return new AliasedEObjectDescription(inputQN, candidate);
				}
			}
			// not accessed via namespace
			QualifiedName nameNoDefault = inputQN.skipLast(1);
			QualifiedName moduleName = nameNoDefault.getSegmentCount() > 1
					? QualifiedName.create(nameNoDefault.getLastSegment())
					: nameNoDefault;
			return new AliasedEObjectDescription(moduleName, candidate);
		}
		// no special handling, return original input
		return candidate;
	}
}
