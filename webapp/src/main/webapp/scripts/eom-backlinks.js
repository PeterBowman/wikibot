// Based on MediaWiki's searchSuggest module.

$( function () {
	function request( query, response, maxRows ) {
		return $.getJSON( 'eom-backlinks/api', {
			search: query,
			limit: maxRows
		}).done( function ( data ) {
			response( data.results );
		} );
	}
	
	$( '#morphem-input' ).suggestions( {
		fetch: function ( query, response, maxRows ) {
			$.data( this[ 0 ], 'request', request( query, response, maxRows ) );
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
	} );;
} );