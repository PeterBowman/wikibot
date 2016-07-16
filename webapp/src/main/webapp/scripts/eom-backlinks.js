$( function () {
	function makeRequest( query, response, maxRows ) {
		return $.getJSON( 'eom-backlinks/api', {
			search: query,
			limit: maxRows
		} ).done( function ( data ) {
			response( data.results );
		} );
	}
	
	// Based on MediaWiki's searchSuggest module.
	$( '#morphem-input' ).suggestions( {
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
	} )
	.focus();
	
	$( '.wikilink[data-section^="esperanto"]' ).definitionPopups();
} );
