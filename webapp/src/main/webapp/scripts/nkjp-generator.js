$( function () {
	var $submit = $( '#submit' ),
		$input = $( '#address' ),
		$form = $( '#nkjp-form' );
	
	function makeRequest( address ) {
		return $.getJSON( 'nkjp-generator', {
			address: address,
			format: 'json'
		} );
	}
	
	function onSuccess( data ) {
		var $pre = $( '<pre>' ).text( data.output ),
			$params = $( '<ul>' );
		
		$.each( data.parameters, function ( i, object ) {
			$( '<li>' ).append(
				$( '<code>' ).text( object.name ),
				': ',
				object.value
			).appendTo( $params );
		} );
		
		return $pre
			.add( $( '<p>' ).text( 'Parametry szablonu:' ) )
			.add( $params );
	}
	
	function onFail( data ) {
		var $backtrace,
			$error = $( '<p>' ).text( 'Błąd: ' + data.error );
		
		if ( data.backtrace && data.backtrace.length ) {
			$backtrace = $( '<ul>' );
			
			$.each( data.backtrace, function ( i, el ) {
				$( '<li>' ).text( el ).appendTo( $backtrace );
			} );
			
			return $error.add( $backtrace );
		} else {
			return $error;
		}
	}
	
	function updateResult( $result ) {
		$form.nextAll().remove();
		
		if ( $result ) {
			$form.after(
				$( '<h2>' ).text( 'Wynik' ),
				$result
			);
		}
	}
	
	function pushHistoryState( address, $result ) {
		var url;
		
		if ( !window.history ) {
			return;
		}
		
		if ( !address ) {
			history.pushState( {
				html: $( '<dummy>' ).append( $form.nextAll( ':not(h2)' ).clone() ).html(),
				address: $input.val()
			}, '', location.search );
			
			return;
		}
		
		url = '?' + $.param( {
			gui: 'on',
			address: address
		} );
		
		history.pushState( {
			html: $( '<dummy>' ).append( $result.clone() ).html(),
			address: address
		}, '', url );
	}
	
	$submit.add( '#example-link' ).on( 'click', function ( evt ) {
		var address = $input.val();
		
		evt.preventDefault();
		
		if ( this.id === 'example-link' ) {
			address = $( this ).text();
			$input.val( address );
		}
		
		if ( !$.trim( address ) ) {
			return;
		}
		
		$submit.prop( 'disabled', true );
		
		makeRequest( address ).done( function ( data ) {
			var $result;
			
			$submit.prop( 'disabled', false );
			
			if ( data.status !== 200 ) {
				$result = onFail( data );
			} else {
				$result = onSuccess( data );
			}
			
			updateResult( $result );
			pushHistoryState( address, $result );
		} ).fail( function ( jqXHR, textStatus, errorThrown ) {
			$form.submit();
		} );
	} );
	
	window.onpopstate =  function ( evt ) {
		if ( !evt.state ) {
			$input.val( '' );
			updateResult();
		} else {
			$input.val( evt.state.address );
			updateResult( $( $.parseHTML( evt.state.html ) ) );
		}
	};
	
	pushHistoryState();
} );