package com.github.wikibot.utils;

import java.text.Collator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

public class CategoryTree implements Iterable<CategoryTree.Node> {
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
    public Iterator<Node> iterator() {
        return new DepthFirstSearch(root);
    }

    public class Node implements Comparable<Node> {
        private final Node parent;
        private final String name;
        private final int members;
        private final SortedSet<Node> children;
        private final int depth;

        private Node(Node parent, String name, int members) {
            this.parent = parent;
            this.name = Objects.requireNonNull(name);
            this.members = members;

            children = new TreeSet<>();

            if (parent == null) {
                depth = 0;
            } else {
                depth = parent.depth + 1;
            }
        }

        public Node getParent() {
            return parent;
        }

        public String getName() {
            return name;
        }

        public int getMembers() {
            return members;
        }

        public SortedSet<Node> getChildren() {
            return children;
        }

        public int getDepth() {
            return depth;
        }

        public Node addChild(String name, int members) {
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
            return Objects.hash(parent, name);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (obj == null) {
                return false;
            }

            var other = (Node)obj;

            if (parent == null || other.parent == null) {
                return false; // can't be both null (two root nodes)
            }

            return parent.equals(other.parent) && name.equals(other.name);
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

    public class DepthFirstSearch implements Iterator<Node> {
        private final Deque<Iterator<Node>> iteratorStack;

        public DepthFirstSearch(Node root) {
            iteratorStack = new ArrayDeque<>();
            iteratorStack.push(root.children.iterator());
        }

        @Override
        public boolean hasNext() {
            while (!iteratorStack.isEmpty() && !iteratorStack.peekLast().hasNext()) {
                iteratorStack.pollLast();
            }

            return !iteratorStack.isEmpty();
        }

        @Override
        public Node next() {
            var next = iteratorStack.peekLast().next();

            if (!next.children.isEmpty()) {
                iteratorStack.add(next.children.iterator());
            }

            return next;
        }
    }
}
