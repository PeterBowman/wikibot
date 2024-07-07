package com.github.wikibot.utils;

import java.text.Collator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public class CategoryTree implements Iterable<CategoryTree.NodeResult> {
    private final Node root;
    private final Collator collator;

    public CategoryTree(String rootData, int rootMembers, Collator collator) {
        root = new Node(null, rootData, rootMembers);
        this.collator = Objects.requireNonNull(collator);
    }

    public Node getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return root.toString();
    }

    @Override
    public Iterator<NodeResult> iterator() {
        return new DepthFirstSearch(root);
    }

    public class Node implements Comparable<Node> {
        private final String name;
        private final int members;
        private final int minDepth;

        private final SortedSet<Node> parents = new TreeSet<>();
        private final SortedSet<Node> children = new TreeSet<>();

        private Node(Node parent, String name, int members) {
            this.name = Objects.requireNonNull(name);
            this.members = members;

            if (parent == null) {
                minDepth = 0;
            } else {
                minDepth = parent.minDepth + 1;
                parents.add(parent);
            }
        }

        public String getName() {
            return name;
        }

        public int getMembers() {
            return members;
        }

        public int getMinDepth() {
            return minDepth;
        }

        public SortedSet<Node> getParents() {
            return parents;
        }

        public SortedSet<Node> getChildren() {
            return children;
        }

        public Node connectChild(Node child) {
            children.add(child);
            child.parents.add(this);
            return child;
        }

        public Node registerChild(String name, int members) {
            var node = new Node(this, name, members);
            children.add(node);
            return node;
        }

        public Optional<Node> getChild(String name) {
            Objects.requireNonNull(name);

            return children.stream()
                .filter(n -> n.name.equals(name))
                .findFirst();
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (obj == null) {
                return false;
            }

            var other = (Node)obj;
            return name.equals(other.name);
        }

        @Override
        public String toString() {
            return String.format("%s (%d members, %d subcats)", name, members, children.size());
        }

        @Override
        public int compareTo(Node o) {
            return collator.compare(name, o.name);
        }
    }

    public static record NodeResult (Node node, int depth) {}

    public static class DepthFirstSearch implements Iterator<NodeResult> {
        private final Deque<Iterator<Node>> iteratorStack;
        private int currentDepth;

        public DepthFirstSearch(Node root) {
            iteratorStack = new ArrayDeque<>();
            iteratorStack.push(root.children.iterator());
            currentDepth = 1;
        }

        @Override
        public boolean hasNext() {
            while (!iteratorStack.isEmpty() && !iteratorStack.peekLast().hasNext()) {
                iteratorStack.pollLast();
                currentDepth--;
            }

            return !iteratorStack.isEmpty();
        }

        @Override
        public NodeResult next() {
            var next = iteratorStack.peekLast().next();
            var depth = currentDepth;

            if (next.minDepth == currentDepth && !next.children.isEmpty()) {
                iteratorStack.add(next.children.iterator());
                currentDepth++;
            }

            return new NodeResult(next, depth);
        }
    }
}
