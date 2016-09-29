if ( window.history && /(\?|&)usecache=/.test( location.href ) ) {
	history.replaceState( '', '', location.href.replace( /&?usecache=[^&]+/, '' ).replace( '?&', '?' ) );
}

$( function () {
	var $conditionalInputs = $( '#showredirects, #showdisambigs' ),
		$includeCreated = $( '#includecreated' );
	
	function makeRequest( query, response, maxRows ) {
		return $.getJSON( 'broken-iw-links/api', {
			search: query,
			limit: maxRows
		} ).done( function ( data ) {
			response( data.results );
		} );
	}
	
	function toggleConditionalInputs( evt ) {
		$conditionalInputs.prop( 'disabled', $( this ).is( ':checked' ) )
	}
	
	$includeCreated.on( 'change', toggleConditionalInputs );
	
	toggleConditionalInputs.apply( $includeCreated );
	
	// Based on MediaWiki's searchSuggest module.
	$( '#targetdb' ).suggestions( {
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
			render: function ( suggestion, context ) {
				this
					.text( suggestion.dbname + ' (' + suggestion.url + ')' )
					.data( 'text', suggestion.dbname );
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
} );