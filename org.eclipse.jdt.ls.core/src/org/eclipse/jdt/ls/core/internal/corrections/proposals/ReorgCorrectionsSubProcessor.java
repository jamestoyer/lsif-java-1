/*******************************************************************************
 * Copyright (c) 2000, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Copied from /org.eclipse.jdt.ui/src/org/eclipse/jdt/internal/ui/text/correction/ReorgCorrectionsSubProcessor.java
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Matt Chapman, mpchapman@gmail.com - 89977 Make JDT .java agnostic
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.corrections.proposals;

import java.util.Collection;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaConventions;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.fix.IProposableFix;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.ui.text.correction.IProblemLocationCore;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.corext.fix.UnusedCodeFix;
import org.eclipse.jdt.ls.core.internal.corext.refactoring.changes.RenameCompilationUnitChange;
import org.eclipse.jdt.ls.core.internal.corrections.CorrectionMessages;
import org.eclipse.jdt.ls.core.internal.corrections.IInvocationContext;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.text.edits.TextEdit;


public class ReorgCorrectionsSubProcessor {

	public static void getWrongTypeNameProposals(IInvocationContext context, IProblemLocationCore problem,
			Collection<CUCorrectionProposal> proposals) {
		ICompilationUnit cu= context.getCompilationUnit();
		boolean isLinked = cu.getResource().isLinked();

		IJavaProject javaProject= cu.getJavaProject();
		String sourceLevel= javaProject.getOption(JavaCore.COMPILER_SOURCE, true);
		String compliance= javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);

		CompilationUnit root= context.getASTRoot();

		ASTNode coveredNode= problem.getCoveredNode(root);
		if (!(coveredNode instanceof SimpleName)) {
			return;
		}

		ASTNode parentType= coveredNode.getParent();
		if (!(parentType instanceof AbstractTypeDeclaration)) {
			return;
		}

		String currTypeName= ((SimpleName) coveredNode).getIdentifier();
		String newTypeName= JavaCore.removeJavaLikeExtension(cu.getElementName());


		boolean hasOtherPublicTypeBefore = false;

		boolean found = false;
		List<AbstractTypeDeclaration> types= root.types();
		for (int i= 0; i < types.size(); i++) {
			AbstractTypeDeclaration curr= types.get(i);
			if (parentType != curr) {
				if (newTypeName.equals(curr.getName().getIdentifier())) {
					return;
				}
				if (!found && Modifier.isPublic(curr.getModifiers())) {
					hasOtherPublicTypeBefore = true;
				}
			} else {
				found = true;
			}
		}

		if (!JavaConventions.validateJavaTypeName(newTypeName, sourceLevel, compliance).matches(IStatus.ERROR)) {
			proposals.add(new CorrectMainTypeNameProposal(cu, context, currTypeName, newTypeName, IProposalRelevance.RENAME_TYPE));
		}

		if (!hasOtherPublicTypeBefore && JavaLanguageServerPlugin.getPreferencesManager().getClientPreferences().isResourceOperationSupported()) {
			String newCUName = JavaModelUtil.getRenamedCUName(cu, currTypeName);
			ICompilationUnit newCU = ((IPackageFragment) (cu.getParent())).getCompilationUnit(newCUName);
			if (!newCU.exists() && !isLinked && !JavaConventions.validateCompilationUnitName(newCUName, sourceLevel, compliance).matches(IStatus.ERROR)) {
				RenameCompilationUnitChange change = new RenameCompilationUnitChange(cu, newCUName);

				// rename CU
				String label = Messages.format(CorrectionMessages.ReorgCorrectionsSubProcessor_renamecu_description, BasicElementLabels.getResourceName(newCUName));
				proposals.add(new CUCorrectionProposal(label, CodeActionKind.QuickFix, cu, change, IProposalRelevance.RENAME_CU));
			}
		}

	}

	public static void getWrongPackageDeclNameProposals(IInvocationContext context, IProblemLocationCore problem,
			Collection<CUCorrectionProposal> proposals) throws CoreException {
		ICompilationUnit cu= context.getCompilationUnit();

		// correct package declaration
		int relevance= cu.getPackageDeclarations().length == 0 ? IProposalRelevance.MISSING_PACKAGE_DECLARATION : IProposalRelevance.CORRECT_PACKAGE_DECLARATION; // bug 38357
		proposals.add(new CorrectPackageDeclarationProposal(cu, problem, relevance));
	}

	public static void removeImportStatementProposals(IInvocationContext context, IProblemLocationCore problem,
			Collection<CUCorrectionProposal> proposals) {
		IProposableFix fix= UnusedCodeFix.createRemoveUnusedImportFix(context.getASTRoot(), problem);
		if (fix != null) {
			try {
				CompilationUnitChange change = fix.createChange(null);
				CUCorrectionProposal proposal = new CUCorrectionProposal(change.getName(), CodeActionKind.QuickFix, change.getCompilationUnit(),
						change, IProposalRelevance.REMOVE_UNUSED_IMPORT);
				proposals.add(proposal);
			} catch (CoreException e) {
				JavaLanguageServerPlugin.log(e);
			}
		}

		final ICompilationUnit cu= context.getCompilationUnit();
		String name= CorrectionMessages.ReorgCorrectionsSubProcessor_organizeimports_description;
		CUCorrectionProposal proposal = new CUCorrectionProposal(name, CodeActionKind.QuickFix, cu, null, IProposalRelevance.ORGANIZE_IMPORTS) {

			@Override
			protected void addEdits(IDocument document, TextEdit editRoot) throws CoreException {
				CompilationUnit astRoot = context.getASTRoot();
				OrganizeImportsOperation op = new OrganizeImportsOperation(cu, astRoot, true, false, true, null);
				editRoot.addChild(op.createTextEdit(null));
			}
		};
		proposals.add(proposal);
	}
}
