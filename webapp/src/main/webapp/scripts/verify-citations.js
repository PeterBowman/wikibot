$( function () {
    function makeRequest( type, query, response, maxRows ) {
        return $.getJSON( 'api', {
            type: type,
            search: query,
            limit: maxRows
        } ).done( function ( data ) {
            response( data.results );
        } );
    }

    if ( typeof $.fn.suggestions === 'function' ) {
        // Based on MediaWiki's searchSuggest module.
        $( '[data-api-type]' ).suggestions( {
            fetch: function ( query, response, maxRows ) {
                var request, type = this.data( 'api-type' );

                if ( type === 'user' || query.charAt(0) !== '#' ) {
                    request = makeRequest( type, query, response, maxRows );
                    $.data( this[ 0 ], 'request', request );
                }
            },
            cancel: function () {
                var node = this[ 0 ];
                var request = $.data( node, 'request' );

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
            cache: true
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
    }

    if ( typeof $.fn.tablesorter === 'function' ) {
        $( '#ranking-sortable' ).tablesorter( {
            headers: {
                0: {
                    sorter: false
                }
            }
        } );
    }
} );
