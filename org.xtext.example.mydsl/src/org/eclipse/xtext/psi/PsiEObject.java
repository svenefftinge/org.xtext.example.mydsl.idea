package org.eclipse.xtext.psi;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.xtext.nodemodel.INode;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;

public interface PsiEObject extends PsiElement {
	
	Key<INode> XTEXT_NODE_KEY = new Key<INode>("XTEXT_NODE_KEY");

	Key<EReference> XTEXT_EREFERENCE_KEY = new Key<EReference>("XTEXT_EREFERENCE_KEY");
	
	INode getINode();
	
	EClass getType();
	
	EReference getEReference();
}
