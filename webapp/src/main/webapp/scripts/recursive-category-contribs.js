$( function () {
    function makeRequest( query, response, maxRows ) {
        return $.ajax( {
            url: 'https://pl.wikipedia.org/w/api.php',
            data: {
                action: 'opensearch',
                search: query,
                namespace: 14, // category
                profile: 'fuzzy',
                redirects: 'resolve',
                limit: maxRows,
                format: 'json',
                formatversion: 2,
                origin: '*'
            },
            contentType: 'application/json',
            dataType: 'json'
        } ).done( function ( data ) {
            response( data[ 1 ].map( function ( pagename ) {
                return pagename.replace( /^Kategoria:/g, '' );
            } ) );
        } );
    }

    // Based on MediaWiki's searchSuggest module.
    $( '#main-category' ).suggestions( {
        fetch: function ( query, response, maxRows ) {
            $.data( this[ 0 ], 'request', makeRequest( query, response, maxRows ) );
        },
        cancel: function () {
            var node = this[ 0 ],
                request = $.data( node, 'request' );

            if ( request ) {
                request.abort();
                $.removeData( node, 'request' );
            }
        },
        result: {
            select: function () {
                return true;
            }
        },
        cache: true,
        maxRows: 10
    } )
    .on( 'paste cut drop', function () {
        $( this ).trigger( 'keypress' );
    } )
    .each( function () {
        var $this = $( this );
        $this
            .data( 'suggestions-context' )
            .data.$container.css( 'fontSize', $this.css( 'fontSize' ) );
    } );
} );
