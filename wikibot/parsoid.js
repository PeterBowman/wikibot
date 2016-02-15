var Parsoid = require('parsoid/');
//var Promise = require('parsoid/node_modules/prfun');

/*var main = Promise.async(function*() {
    var text = "I love wikitext!";
    var pdoc = yield Parsoid.parse(text, { pdoc: true });
    console.log(pdoc.document.outerHTML);
});

main().done();*/

var text = "I has a template! {{foo|bar|baz|eggs=spam}} See it?\n";

var prom = Parsoid.parse(text, { document: true }).then(function (pdoc) {
	console.log(pdoc.out.outerHTML);
	console.log(pdoc.document.outerHTML);
});
//console.log(Promise);
//console.log(pdoc.toWikitext());
//console.log(Parsoid);

//var text = "I has a template! {{foo|bar|baz|eggs=spam}} See it?\n";
/*var promise = Parsoid.parse(text, { pdoc: true });

promise.done( function ( res ) {
	console.log( res );
} );*/

//list.add(text);
//print(list)
