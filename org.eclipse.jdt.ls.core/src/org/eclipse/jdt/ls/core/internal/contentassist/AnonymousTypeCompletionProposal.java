/*******************************************************************************
 * Copyright (c) 2000, 2016 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Code copied from org.eclipse.jdt.internal.ui.text.java.AnonymousTypeCompletionProposal
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Stephan Herrmann - Contribution for Bug 463360 - [override method][null] generating method override should not create redundant null annotations
 *******************************************************************************/
package org.eclipse.jdt.ls.core.internal.contentassist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ITrackedNodePosition;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.IASTSharedValues;
import org.eclipse.jdt.internal.corext.util.CodeFormatterUtil;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.ls.core.internal.corext.codemanipulation.StubUtility2;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.text.edits.MalformedTreeException;

/**
 * Generates Anonymous Class completion proposals.
 */
public class AnonymousTypeCompletionProposal {

	private ICompilationUnit fCompilationUnit;
	private int fReplacementOffset;
	private IType fSuperType;
	private IJavaProject fJavaProject;
	private String fDeclarationSignature;
	private boolean fSnippetSupport;

	public AnonymousTypeCompletionProposal(ICompilationUnit cu, int replacementOffset, IType superType, String declarationSignature, boolean snippetSupport) {
		Assert.isNotNull(cu.getJavaProject());
		Assert.isNotNull(superType);
		Assert.isNotNull(cu);
		Assert.isNotNull(declarationSignature);

		fCompilationUnit = cu;
		fReplacementOffset = replacementOffset;
		fJavaProject = cu.getJavaProject();
		fDeclarationSignature = declarationSignature;
		fSuperType = superType;
		fSnippetSupport = snippetSupport;
	}

	/*
	 * @see JavaTypeCompletionProposal#updateReplacementString(IDocument,char,int,ImportRewrite)
	 */
	public String updateReplacementString(IDocument document, int offset, ImportRewrite impRewrite) throws CoreException, BadLocationException {
		String newBody = createNewBody(impRewrite);
		if (newBody == null) {
			return null;
		}
		StringBuffer buf = new StringBuffer("new A()"); //$NON-NLS-1$
		buf.append(newBody);
		// use the code formatter
		String lineDelim = TextUtilities.getDefaultLineDelimiter(document);
		final IJavaProject project = fCompilationUnit.getJavaProject();
		IRegion lineInfo = document.getLineInformationOfOffset(fReplacementOffset);
		Map<String, String> options = project != null ? project.getOptions(true) : JavaCore.getOptions();
		String replacementString = CodeFormatterUtil.format(CodeFormatter.K_EXPRESSION, buf.toString(), 0, lineDelim, options);
		int lineEndOffset = lineInfo.getOffset() + lineInfo.getLength();
		int p = offset;
		if (p < document.getLength()) {
			char ch = document.getChar(p);
			while (p < lineEndOffset) {
				if (ch == '(' || ch == ')' || ch == ';' || ch == ',') {
					break;
				}
				ch = document.getChar(++p);
			}
			if (ch != ';' && ch != ',' && ch != ')') {
				replacementString = replacementString + ';';
			}
		}
		int beginIndex = replacementString.indexOf('(');
		replacementString = replacementString.substring(beginIndex);
		return replacementString;
	}

	private String createNewBody(ImportRewrite importRewrite) throws CoreException {
		if (importRewrite == null) {
			return null;
		}
		ICompilationUnit workingCopy = null;
		try {
			String name = "Type" + System.currentTimeMillis(); //$NON-NLS-1$
			workingCopy = fCompilationUnit.getPrimary().getWorkingCopy(null);
			ISourceRange range = fSuperType.getSourceRange();
			boolean sameUnit = range != null && fCompilationUnit.equals(fSuperType.getCompilationUnit());
			String dummyClassContent = createDummyType(name);
			StringBuffer workingCopyContents = new StringBuffer(fCompilationUnit.getSource());
			int insertPosition;
			if (sameUnit) {
				insertPosition = range.getOffset() + range.getLength();
			} else {
				ISourceRange firstTypeRange = fCompilationUnit.getTypes()[0].getSourceRange();
				insertPosition = firstTypeRange.getOffset();
			}
			if (fSuperType.isLocal()) {
				workingCopyContents.insert(insertPosition, '{' + dummyClassContent + '}');
				insertPosition++;
			} else {
				workingCopyContents.insert(insertPosition, dummyClassContent + "\n\n"); //$NON-NLS-1$
			}
			workingCopy.getBuffer().setContents(workingCopyContents.toString());
			ASTParser parser = ASTParser.newParser(IASTSharedValues.SHARED_AST_LEVEL);
			parser.setResolveBindings(true);
			parser.setStatementsRecovery(true);
			parser.setSource(workingCopy);
			CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());
			ASTNode newType = NodeFinder.perform(astRoot, insertPosition, dummyClassContent.length());
			if (!(newType instanceof AbstractTypeDeclaration)) {
				return null;
			}
			AbstractTypeDeclaration declaration = (AbstractTypeDeclaration) newType;
			ITypeBinding dummyTypeBinding = declaration.resolveBinding();
			if (dummyTypeBinding == null) {
				return null;
			}
			IMethodBinding[] bindings = StubUtility2.getOverridableMethods(astRoot.getAST(), dummyTypeBinding, true);
			if (fSuperType.isInterface()) {
				ITypeBinding[] dummySuperInterfaces = dummyTypeBinding.getInterfaces();
				if (dummySuperInterfaces.length == 0 || dummySuperInterfaces.length == 1 && dummySuperInterfaces[0].isRawType()) {
					bindings = new IMethodBinding[0];
				}
			} else {
				ITypeBinding dummySuperclass = dummyTypeBinding.getSuperclass();
				if (dummySuperclass == null || dummySuperclass.isRawType()) {
					bindings = new IMethodBinding[0];
				}
			}
			CodeGenerationSettings settings = PreferenceManager.getCodeGenerationSettings(fJavaProject.getProject());
			IMethodBinding[] methodsToOverride = null;
			settings.createComments = false;
			List<IMethodBinding> result = new ArrayList<>();
			for (int i = 0; i < bindings.length; i++) {
				IMethodBinding curr = bindings[i];
				if (Modifier.isAbstract(curr.getModifiers())) {
					result.add(curr);
				}
			}
			methodsToOverride = result.toArray(new IMethodBinding[result.size()]);
			IBinding contextBinding = null; // used to find @NonNullByDefault effective at that current context
			if (fCompilationUnit.getJavaProject().getOption(JavaCore.COMPILER_ANNOTATION_NULL_ANALYSIS, true).equals(JavaCore.ENABLED)) {
				ASTNode focusNode = NodeFinder.perform(astRoot, fReplacementOffset + dummyClassContent.length(), 0);
				contextBinding = ASTNodes.getEnclosingDeclaration(focusNode);
			}
			ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
			ITrackedNodePosition trackedDeclaration = rewrite.track(declaration);
			ListRewrite rewriter = rewrite.getListRewrite(declaration, declaration.getBodyDeclarationsProperty());
			for (int i = 0; i < methodsToOverride.length; i++) {
				boolean snippetSupport = i == methodsToOverride.length-1 ? fSnippetSupport : false;
				IMethodBinding curr = methodsToOverride[i];
				MethodDeclaration stub = StubUtility2.createImplementationStub(workingCopy, rewrite, importRewrite, null, curr, dummyTypeBinding, settings, dummyTypeBinding.isInterface(), contextBinding, snippetSupport);
				rewriter.insertFirst(stub, null);
			}
			IDocument document = new Document(workingCopy.getSource());
			try {
				rewrite.rewriteAST().apply(document);
				int bodyStart = trackedDeclaration.getStartPosition() + dummyClassContent.indexOf('{');
				int bodyEnd = trackedDeclaration.getStartPosition() + trackedDeclaration.getLength();
				return document.get(bodyStart, bodyEnd - bodyStart);
			} catch (MalformedTreeException exception) {
				JavaLanguageServerPlugin.logException(exception.getMessage(), exception);
			} catch (BadLocationException exception) {
				JavaLanguageServerPlugin.logException(exception.getMessage(), exception);
			}
			return null;
		} finally {
			if (workingCopy != null) {
				workingCopy.discardWorkingCopy();
			}
		}
	}

	private String createDummyType(String name) throws JavaModelException {
		StringBuffer buffer = new StringBuffer();
		buffer.append("abstract class "); //$NON-NLS-1$
		buffer.append(name);
		if (fSuperType.isInterface()) {
			buffer.append(" implements "); //$NON-NLS-1$
		} else {
			buffer.append(" extends "); //$NON-NLS-1$
		}
		if (fDeclarationSignature != null) {
			buffer.append(Signature.toString(fDeclarationSignature));
		} else {
			buffer.append(fSuperType.getFullyQualifiedParameterizedName());
		}
		buffer.append(" {"); //$NON-NLS-1$
		buffer.append("\n"); //$NON-NLS-1$
		buffer.append("}"); //$NON-NLS-1$
		return buffer.toString();
	}

}
