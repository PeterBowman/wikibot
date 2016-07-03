$( function () {
	var $conditionalInputs = $( '#showredirects, #showdisambigs' ),
		$includeCreated = $( '#includecreated' );
	
	function toggleConditionalInputs( evt ) {
		$conditionalInputs.prop( 'disabled', $( this ).is( ':checked' ) )
	}
	
	$includeCreated.on( 'change', toggleConditionalInputs );
	
	toggleConditionalInputs.apply( $includeCreated );
} );